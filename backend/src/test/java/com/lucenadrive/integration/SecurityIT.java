package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.admin.security.Totp;
import com.lucenadrive.security.Role;
import com.lucenadrive.support.FakeAuthAdmin;
import com.lucenadrive.support.TestCpfs;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/** Permissões aplicadas no backend, MFA, cadastro e limites de requisição. */
class SecurityIT extends IntegrationTestBase {

    @Autowired FakeAuthAdmin authAdmin;

    private Map<String, Object> passengerBody(String cpf, String birth) {
        return Map.of("fullName", "Pessoa Teste Lima", "cpf", cpf, "birthDate", birth, "phoneNumber", "+5583999990001",
                "acceptTerms", true, "acceptPrivacy", true, "termsVersion", "2026-10", "privacyVersion", "2026-10");
    }

    @Test
    void registrationValidatesCpfAgeAndUniqueness() {
        String u1 = uid("p");
        Actor a = new Actor(u1, tokens.register(u1, u1 + "@x.test", true), null);
        JsonNode bad = call(HttpMethod.POST, "/api/v1/passengers/register", a, passengerBody("111.111.111-11", "1990-01-01"), 400);
        assertThat(bad.get("fieldErrors").get(0).get("field").asString()).isEqualTo("cpf");
        call(HttpMethod.POST, "/api/v1/passengers/register", a, passengerBody(TestCpfs.next(), "2015-01-01"), 422);
        String cpf = TestCpfs.next();
        call(HttpMethod.POST, "/api/v1/passengers/register", a, passengerBody(cpf, "1990-01-01"), 201);
        call(HttpMethod.POST, "/api/v1/passengers/register", a, passengerBody(cpf, "1990-01-01"), 409);
        String u2 = uid("p");
        Actor b = new Actor(u2, tokens.register(u2, u2 + "@x.test", true), null);
        JsonNode dup = call(HttpMethod.POST, "/api/v1/passengers/register", b, passengerBody(cpf, "1990-01-01"), 409);
        assertThat(dup.get("code").asString()).isEqualTo("CPF_ALREADY_REGISTERED");
        // Perfil não expõe CPF completo
        JsonNode me = call(HttpMethod.GET, "/api/v1/me", a, null, 200);
        assertThat(me.get("cpfMasked").asString()).startsWith("***.").doesNotContain(cpf.substring(0, 3));
        // Terms not accepted
        String u3 = uid("p");
        Actor c = new Actor(u3, tokens.register(u3, u3 + "@x.test", true), null);
        Map<String, Object> noTerms = new java.util.HashMap<>(passengerBody(TestCpfs.next(), "1990-01-01"));
        noTerms.put("acceptTerms", false);
        call(HttpMethod.POST, "/api/v1/passengers/register", c, noTerms, 400);
    }

    @Test
    void roleBasedAccessIsEnforcedOnServer() {
        call(HttpMethod.GET, "/api/v1/me", null, null, 401);
        call(HttpMethod.GET, "/api/v1/me", new Actor("x", "forged-token", null), null, 401);
        Actor pax = passenger();
        call(HttpMethod.GET, "/api/v1/admin/dashboard/summary", pax, null, 403);
        // Cabeçalho MFA forjado não concede acesso
        call(HttpMethod.GET, "/api/v1/admin/dashboard/summary", new Actor(pax.uid(), pax.token(), "abc.def"), null, 403);
        call(HttpMethod.POST, "/api/v1/drivers/me/online", pax, Map.of("lat", 0, "lng", 0), 403);

        Actor admin = staffWithMfa(Role.ADMIN);
        String cityId = seedCityWithFares(admin, "Cidade Segurança");
        Actor driver = approvedOnlineDriver(admin, cityId, LAT, LNG);
        call(HttpMethod.PUT, "/api/v1/admin/cities/" + cityId + "/fares/ESSENCIAL", driver, Map.of(), 403);
        call(HttpMethod.POST, "/api/v1/admin/drivers/" + driver.uid() + "/status", pax,
                Map.of("status", "SUSPENDED", "reason", "tentativa"), 403);

        // Equipe sem MFA
        Actor support = staffWithMfa(Role.SUPPORT);
        JsonNode noMfa = call(HttpMethod.GET, "/api/v1/admin/dashboard/summary", new Actor(support.uid(), support.token(), null),
                null, 403);
        assertThat(noMfa.get("code").asString()).isEqualTo("MFA_REQUIRED");
        // Suporte não altera tarifa, não saca, não aprova motoristas
        putFareExpect(support, cityId, 403);
        call(HttpMethod.POST, "/api/v1/admin/finance/withdrawals", support, Map.of("amountCents", 1000), 403, idem());
        call(HttpMethod.POST, "/api/v1/admin/drivers/" + driver.uid() + "/status", support,
                Map.of("status", "SUSPENDED", "reason", "tentativa"), 403);

        // Financeiro não cria proprietário nem concede papéis
        Actor finance = staffWithMfa(Role.FINANCE);
        call(HttpMethod.POST, "/api/v1/admin/staff/roles", finance, Map.of("email", pax.uid() + "@exemplo.test",
                "role", "OWNER", "grant", true, "reason", "tentativa de escalonamento"), 403);
        // Admin não se promove, não concede OWNER/FINANCE; pode conceder SUPPORT a outra pessoa
        call(HttpMethod.POST, "/api/v1/admin/staff/roles", admin, Map.of("email", admin.uid() + "@lucena.test",
                "role", "SUPPORT", "grant", true, "reason", "auto concessão proibida"), 403);
        call(HttpMethod.POST, "/api/v1/admin/staff/roles", admin, Map.of("email", pax.uid() + "@exemplo.test",
                "role", "OWNER", "grant", true, "reason", "tentativa de escalonamento"), 403);
        call(HttpMethod.POST, "/api/v1/admin/staff/roles", admin, Map.of("email", pax.uid() + "@exemplo.test",
                "role", "FINANCE", "grant", true, "reason", "tentativa de escalonamento"), 403);
        JsonNode granted = call(HttpMethod.POST, "/api/v1/admin/staff/roles", admin, Map.of("email", pax.uid() + "@exemplo.test",
                "role", "SUPPORT", "grant", true, "reason", "Atendente contratado em outubro"), 200);
        assertThat(granted.get("roles").toString()).contains("SUPPORT", "PASSENGER");
        JsonNode audit = call(HttpMethod.GET, "/api/v1/admin/audit?targetId=" + pax.uid(), admin, null, 200);
        assertThat(audit.get("items").get(0).get("action").asString()).isEqualTo("STAFF_ROLE_GRANTED");

        // Suspensão: bloqueia operações, revoga sessões e preserva histórico
        call(HttpMethod.POST, "/api/v1/admin/users/" + driver.uid() + "/status", admin,
                Map.of("status", "SUSPENDED", "reason", "Investigação de ocorrência"), 200);
        assertThat(authAdmin.revoked).contains(driver.uid());
        call(HttpMethod.GET, "/api/v1/driver/offers", driver, null, 403);
        call(HttpMethod.GET, "/api/v1/admin/drivers/" + driver.uid(), admin, null, 200);
    }

    private void putFareExpect(Actor actor, String cityId, int status) {
        call(HttpMethod.PUT, "/api/v1/admin/cities/" + cityId + "/fares/ESSENCIAL", actor, Map.of(
                "config", Map.of("baseFareCents", 1, "perKmCents", 1, "perMinuteCents", 1, "minimumFareCents", 1,
                        "cancellationFeeCents", 1, "commissionBp", 1, "demandMultiplierBp", 10_000,
                        "maxMultiplierBp", 10_000, "active", true), "reason", "tentativa não autorizada"), status);
    }

    @Test
    void mfaLockoutReplayStepUpAndSessionRevocation() {
        String uid = uid("staff");
        String token = tokens.register(uid, uid + "@lucena.test", true);
        store.set("users", uid, new com.lucenadrive.user.User(uid, uid + "@lucena.test", "Admin", null, null,
                java.util.EnumSet.of(Role.ADMIN), com.lucenadrive.user.UserStatus.ACTIVE, null,
                com.lucenadrive.user.NotificationPreferences.defaults(), null, null, null, null, clock.instant(), clock.instant()));
        Actor noMfa = new Actor(uid, token, null);
        // Verificar antes de cadastrar autenticador
        call(HttpMethod.POST, "/api/v1/admin/auth/verify", noMfa, Map.of("code", "123456"), 403);
        // Cadastro exige login recente
        tokens.setStaleLogin(uid, clock.instant().minus(Duration.ofHours(1)));
        call(HttpMethod.POST, "/api/v1/admin/auth/totp/enroll", noMfa, null, 403);
        tokens.setStaleLogin(uid, null);
        String secret = call(HttpMethod.POST, "/api/v1/admin/auth/totp/enroll", noMfa, null, 200).get("secret").asString();
        String code = Totp.code(secret, Totp.timeStep(clock.instant().getEpochSecond()));
        String recovery = call(HttpMethod.POST, "/api/v1/admin/auth/totp/confirm", noMfa, Map.of("code", code), 200)
                .get("recoveryCodes").get(0).asString();
        // Reutilização do mesmo código (replay) é recusada
        call(HttpMethod.POST, "/api/v1/admin/auth/verify", noMfa, Map.of("code", code), 401);
        // Código de recuperação de uso único
        JsonNode viaRecovery = call(HttpMethod.POST, "/api/v1/admin/auth/verify", noMfa, Map.of("recoveryCode", recovery), 200);
        assertThat(viaRecovery.get("usedRecoveryCode").asBoolean()).isTrue();
        call(HttpMethod.POST, "/api/v1/admin/auth/verify", noMfa, Map.of("recoveryCode", recovery), 401);
        // Bloqueio após tentativas falhas (contando a falha anterior)
        for (int i = 0; i < 3; i++) {
            call(HttpMethod.POST, "/api/v1/admin/auth/verify", noMfa, Map.of("code", "000000"), 401);
        }
        call(HttpMethod.POST, "/api/v1/admin/auth/verify", noMfa, Map.of("code", "000001"), 401);
        clock.advance(Duration.ofSeconds(30));
        String valid = Totp.code(secret, Totp.timeStep(clock.instant().getEpochSecond()));
        JsonNode locked = call(HttpMethod.POST, "/api/v1/admin/auth/verify", noMfa, Map.of("code", valid), 429);
        assertThat(locked.get("code").asString()).isEqualTo("MFA_LOCKED");
        clock.advance(Duration.ofMinutes(16));
        valid = Totp.code(secret, Totp.timeStep(clock.instant().getEpochSecond()));
        String session = call(HttpMethod.POST, "/api/v1/admin/auth/verify", noMfa, Map.of("code", valid), 200)
                .get("sessionToken").asString();
        Actor admin = new Actor(uid, token, session);
        call(HttpMethod.GET, "/api/v1/admin/dashboard/summary", admin, null, 200);

        // Step-up: operação crítica após a janela exige nova confirmação
        String cityId = call(HttpMethod.POST, "/api/v1/admin/cities", admin, Map.of("name", "Cidade MFA", "state", "PB",
                "center", Map.of("lat", LAT, "lng", LNG), "active", true), 201).get("id").asString();
        clock.advance(Duration.ofMinutes(6));
        JsonNode stepUpNeeded = call(HttpMethod.PUT, "/api/v1/admin/cities/" + cityId + "/fares/ESSENCIAL", admin, Map.of(
                "config", Map.of("baseFareCents", 500, "perKmCents", 200, "perMinuteCents", 30, "minimumFareCents", 800,
                        "cancellationFeeCents", 700, "commissionBp", 2000, "demandMultiplierBp", 10_000,
                        "maxMultiplierBp", 20_000, "active", true), "reason", "Tarifa de abertura da cidade"), 403);
        assertThat(stepUpNeeded.get("code").asString()).isEqualTo("MFA_REQUIRED");
        call(HttpMethod.POST, "/api/v1/admin/auth/step-up", admin,
                Map.of("code", Totp.code(secret, Totp.timeStep(clock.instant().getEpochSecond()))), 200);
        putFare(admin, cityId, "ESSENCIAL", 500, 200, 2000, "Tarifa de abertura da cidade");

        // Revogação de sessão
        JsonNode sessions = call(HttpMethod.GET, "/api/v1/admin/auth/sessions", admin, null, 200);
        assertThat(sessions.size()).isGreaterThanOrEqualTo(2);
        call(HttpMethod.POST, "/api/v1/admin/auth/logout", admin, null, 204);
        call(HttpMethod.GET, "/api/v1/admin/dashboard/summary", admin, null, 403);
        JsonNode events = call(HttpMethod.GET, "/api/v1/admin/security-events?uid=" + uid,
                staffWithMfa(Role.OWNER), null, 200);
        assertThat(events.valueStream().map(e -> e.get("type").asString()))
                .contains("TOTP_ENROLLED", "MFA_FAILURE", "MFA_LOCKED", "MFA_SUCCESS", "NEW_DEVICE", "STEP_UP");
    }

    @Test
    void unverifiedEmailAndRateLimits() {
        String uid = uid("pax");
        Actor unverified = new Actor(uid, tokens.register(uid, uid + "@x.test", false), null);
        call(HttpMethod.POST, "/api/v1/passengers/register", unverified, passengerBody(TestCpfs.next(), "1990-01-01"), 201);
        JsonNode r = call(HttpMethod.POST, "/api/v1/rides/estimate", unverified, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.01, LNG, "B")), 403);
        assertThat(r.get("code").asString()).isEqualTo("EMAIL_NOT_VERIFIED");

        // Limite de mensagens no chat e de atualizações de localização
        Actor admin = staffWithMfa(Role.ADMIN);
        String cityId = seedCityWithFares(admin, "Cidade Limites");
        Actor driver = approvedOnlineDriver(admin, cityId, LAT, LNG);
        Actor pax = passenger();
        JsonNode est = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.02, LNG, "B")), 200);
        String rideId = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId",
                est.get("quotes").get(0).get("quoteId").asString(), "paymentMethod", "CASH"), 201, idem()).get("id").asString();
        call(HttpMethod.POST, "/api/v1/driver/offers/" + rideId + "/accept", driver, null, 200);
        for (int i = 0; i < 10; i++) {
            call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/chat/messages", pax,
                    Map.of("clientMessageId", UUID.randomUUID().toString(), "text", "mensagem " + i), 201);
        }
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/chat/messages", pax,
                Map.of("clientMessageId", UUID.randomUUID().toString(), "text", "excesso"), 429);
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/chat/messages", pax,
                Map.of("clientMessageId", UUID.randomUUID().toString(), "text", "x".repeat(501)), 400);
        call(HttpMethod.POST, "/api/v1/driver/location", driver, Map.of("lat", LAT, "lng", LNG), 204);
        call(HttpMethod.POST, "/api/v1/driver/location", driver, Map.of("lat", LAT, "lng", LNG), 429);

        // Saques sem provedor: indisponível de forma explícita
        JsonNode payout = call(HttpMethod.POST, "/api/v1/drivers/me/finance/payouts", driver, Map.of("amountCents", 5000),
                503, idem());
        assertThat(payout.get("code").asString()).isEqualTo("INTEGRATION_NOT_CONFIGURED");
        JsonNode cfg = call(HttpMethod.GET, "/api/v1/public/config", null, null, 200);
        assertThat(cfg.get("paymentsConfigured").asBoolean()).isFalse();
        assertThat(cfg.get("paymentMethods").toString()).isEqualTo("[\"CASH\"]");
    }
}
