package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.lucenadrive.admin.security.Totp;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.security.Role;
import com.lucenadrive.support.FakeIdTokenVerifier;
import com.lucenadrive.support.FakeObjectStorage;
import com.lucenadrive.support.MutableClock;
import com.lucenadrive.support.TestBeans;
import com.lucenadrive.support.TestCpfs;
import com.lucenadrive.user.NotificationPreferences;
import com.lucenadrive.user.User;
import com.lucenadrive.user.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base dos testes de integração: contexto Spring completo (segurança, validação, serviços,
 * controllers) com Firestore/Firebase substituídos por implementações em memória.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestBeans.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegrationTestBase {

    private static final java.util.concurrent.atomic.AtomicInteger AREAS = new java.util.concurrent.atomic.AtomicInteger();

    // Área fictícia de testes, distinta por método de teste para não haver sobreposição entre cidades.
    protected final double LAT = -5.0 - AREAS.incrementAndGet();
    protected final double LNG = -40.0;

    @Autowired protected MockMvc mvc;
    @Autowired protected JsonMapper json;
    @Autowired protected FakeIdTokenVerifier tokens;
    @Autowired protected MutableClock clock;
    @Autowired protected DocStore store;
    @Autowired protected FakeObjectStorage storage;

    /** Ator de teste: token Firebase fictício + token de sessão MFA (equipe). */
    public record Actor(String uid, String token, String mfa) {
    }

    protected JsonNode call(HttpMethod method, String path, Actor actor, Object body, int expected) {
        return call(method, path, actor, body, expected, Map.of());
    }

    protected JsonNode call(HttpMethod method, String path, Actor actor, Object body, int expected,
                            Map<String, String> headers) {
        try {
            MockHttpServletRequestBuilder req = request(method, path).contentType(MediaType.APPLICATION_JSON);
            if (actor != null) {
                req.header("Authorization", "Bearer " + actor.token());
                if (actor.mfa() != null) {
                    req.header("X-Lucena-MFA", actor.mfa());
                }
            }
            headers.forEach(req::header);
            if (body != null) {
                req.content(json.writeValueAsBytes(body));
            }
            MvcResult res = mvc.perform(req).andReturn();
            String content = res.getResponse().getContentAsString();
            assertThat(res.getResponse().getStatus()).as("%s %s → %s", method, path, content).isEqualTo(expected);
            return content.isBlank() ? json.nullNode() : json.readTree(content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger PLATES = new java.util.concurrent.atomic.AtomicInteger();

    /** Placas fictícias no padrão Mercosul (TST1A23...). */
    protected static String nextPlate() {
        int n = PLATES.incrementAndGet();
        return "TST" + (n % 10) + (char) ('A' + (n / 10) % 26) + String.format("%02d", (n / 260) % 100);
    }

    protected static String uid(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static Map<String, Object> place(double lat, double lng, String address) {
        return Map.of("lat", lat, "lng", lng, "address", address);
    }

    protected static Map<String, String> idem() {
        return Map.of("Idempotency-Key", UUID.randomUUID().toString());
    }

    // ------------------------------------------------------------------ equipe

    /** Cria membro da equipe (como o provisionamento seguro faria) e conclui o MFA. */
    protected Actor staffWithMfa(Role... roles) {
        String uid = uid("staff");
        String token = tokens.register(uid, uid + "@lucena.test", true);
        Instant now = clock.instant();
        store.set(Collections.USERS, uid, new User(uid, uid + "@lucena.test", "Equipe Teste", null, null,
                EnumSet.copyOf(List.of(roles)), UserStatus.ACTIVE, null, NotificationPreferences.defaults(), null, null,
                now, null, now, now));
        Actor noMfa = new Actor(uid, token, null);
        JsonNode enroll = call(HttpMethod.POST, "/api/v1/admin/auth/totp/enroll", noMfa, null, 200);
        String secret = enroll.get("secret").asString();
        JsonNode confirm = call(HttpMethod.POST, "/api/v1/admin/auth/totp/confirm", noMfa,
                Map.of("code", Totp.code(secret, Totp.timeStep(clock.instant().getEpochSecond()))), 200);
        assertThat(confirm.get("recoveryCodes")).hasSize(10);
        clock.advance(Duration.ofSeconds(31)); // anti-replay: próximo código precisa de novo passo
        JsonNode verify = call(HttpMethod.POST, "/api/v1/admin/auth/verify", noMfa,
                Map.of("code", Totp.code(secret, Totp.timeStep(clock.instant().getEpochSecond()))), 200);
        return new Actor(uid, token, verify.get("sessionToken").asString());
    }

    // ------------------------------------------------------------------ cenário

    protected String seedCityWithFares(Actor admin, String cityName) {
        JsonNode city = call(HttpMethod.POST, "/api/v1/admin/cities", admin, Map.of("name", cityName, "state", "PB",
                "center", Map.of("lat", LAT, "lng", LNG), "active", true), 201);
        String cityId = city.get("id").asString();
        call(HttpMethod.POST, "/api/v1/admin/areas", admin, Map.of("cityId", cityId, "name", "Cobertura", "type", "COVERAGE",
                "active", true, "polygon", List.of(Map.of("lat", LAT - 0.2, "lng", LNG - 0.2), Map.of("lat", LAT - 0.2, "lng", LNG + 0.2),
                        Map.of("lat", LAT + 0.2, "lng", LNG + 0.2), Map.of("lat", LAT + 0.2, "lng", LNG - 0.2))), 201);
        putFare(admin, cityId, "ESSENCIAL", 500, 200, 2000, "Tarifa inicial de lançamento");
        return cityId;
    }

    protected JsonNode putFare(Actor admin, String cityId, String category, long base, long perKm, long commissionBp,
                               String reason) {
        return call(HttpMethod.PUT, "/api/v1/admin/cities/" + cityId + "/fares/" + category, admin, Map.of(
                "config", Map.of("baseFareCents", base, "perKmCents", perKm, "perMinuteCents", 30, "minimumFareCents", 800,
                        "cancellationFeeCents", 700, "commissionBp", commissionBp, "demandMultiplierBp", 10_000,
                        "maxMultiplierBp", 20_000, "timeWindows", List.of(), "active", true),
                "reason", reason), 200);
    }

    protected Actor passenger() {
        String uid = uid("pax");
        String token = tokens.register(uid, uid + "@exemplo.test", true);
        Actor a = new Actor(uid, token, null);
        call(HttpMethod.POST, "/api/v1/passengers/register", a, Map.of("fullName", "Passageira Teste Silva",
                "cpf", TestCpfs.next(), "birthDate", "1995-04-10", "phoneNumber", "+5583999990000",
                "acceptTerms", true, "acceptPrivacy", true, "termsVersion", "2026-10", "privacyVersion", "2026-10"), 201);
        return a;
    }

    /** Envia a posição do motorista (avança o relógio para respeitar o limite de frequência). */
    protected void driverAt(Actor driver, double lat, double lng) {
        clock.advance(java.time.Duration.ofSeconds(3));
        call(HttpMethod.POST, "/api/v1/driver/location", driver, Map.of("lat", lat, "lng", lng), 204);
    }

    /** Motorista completo: cadastro, veículo, documentos, envio, aprovação pelo admin e online. */
    protected Actor approvedOnlineDriver(Actor admin, String cityId, double lat, double lng) {
        String uid = uid("drv");
        String token = tokens.register(uid, uid + "@exemplo.test", true);
        Actor d = new Actor(uid, token, null);
        call(HttpMethod.POST, "/api/v1/drivers/register", d, Map.ofEntries(Map.entry("fullName", "Motorista Teste Souza"),
                Map.entry("cpf", TestCpfs.next()), Map.entry("birthDate", "1988-02-01"),
                Map.entry("phoneNumber", "+5583988880000"), Map.entry("acceptTerms", true), Map.entry("acceptPrivacy", true),
                Map.entry("termsVersion", "2026-10"), Map.entry("privacyVersion", "2026-10"),
                Map.entry("cnhNumber", "12345678901"), Map.entry("cnhCategory", "B"),
                Map.entry("cnhExpiresOn", "2030-01-01"), Map.entry("cnhEar", true), Map.entry("cityId", cityId)), 201);
        JsonNode vehicle = call(HttpMethod.POST, "/api/v1/drivers/me/vehicles", d, Map.of("make", "Fabricante", "model", "Modelo",
                "year", 2022, "color", "Prata", "plate", nextPlate(), "category", "ESSENCIAL"), 201);
        String vehicleId = vehicle.get("id").asString();
        for (String type : List.of("CNH", "CRIMINAL_RECORD", "PROFILE_PHOTO", "CRLV")) {
            String path = "driverDocuments/" + uid + "/" + type + ".pdf";
            storage.put(path, "application/pdf", 50_000);
            Map<String, Object> body = new java.util.HashMap<>(Map.of("type", type, "storagePath", path));
            if (type.equals("CRLV")) {
                body.put("vehicleId", vehicleId);
            }
            call(HttpMethod.POST, "/api/v1/drivers/me/documents", d, body, 200);
        }
        call(HttpMethod.POST, "/api/v1/drivers/me/submit", d, null, 200);
        for (String type : List.of("CNH", "CRIMINAL_RECORD", "PROFILE_PHOTO")) {
            call(HttpMethod.POST, "/api/v1/admin/drivers/" + uid + "/documents/review", admin,
                    Map.of("type", type, "approve", true), 200);
        }
        call(HttpMethod.POST, "/api/v1/admin/drivers/" + uid + "/documents/review", admin,
                Map.of("type", "CRLV", "vehicleId", vehicleId, "approve", true), 200);
        call(HttpMethod.POST, "/api/v1/admin/drivers/vehicles/" + vehicleId + "/status", admin,
                Map.of("status", "APPROVED"), 200);
        call(HttpMethod.POST, "/api/v1/admin/drivers/" + uid + "/status", admin,
                Map.of("status", "APPROVED", "reason", "Documentação conferida"), 200);
        call(HttpMethod.POST, "/api/v1/drivers/me/online", d, Map.of("lat", lat, "lng", lng), 200);
        return d;
    }
}
