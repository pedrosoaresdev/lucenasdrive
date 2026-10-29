package com.lucenadrive.presence;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.scheduling.SchedulerLock;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.RateLimiter;
import com.lucenadrive.security.Role;
import com.lucenadrive.user.User;
import com.lucenadrive.user.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Localização de usuários para o painel "O olho de Deus" (somente OWNER/ADMIN). Participação opcional:
 * só aparece quem ativou o compartilhamento; desativar apaga a posição na hora. Cada abertura do painel é auditada.
 */
@Service
public class PresenceService {

    /** Posição considerada "ao vivo" no painel. */
    static final Duration LIVE = Duration.ofMinutes(5);
    /** Posições mais antigas que isto são apagadas. */
    static final Duration PURGE_AFTER = Duration.ofMinutes(30);

    public record ConsentView(boolean enabled, String version, Instant changedAt, String currentVersion) {
    }

    public record LivePosition(String userId, String name, Presence.Kind kind, double lat, double lng, Double accuracyM,
                               Instant updatedAt, String ip, String device, String cityId, String cityName) {
    }

    /** Cidades atendidas e suas áreas de cobertura, em cache curto (evita leituras a cada posição recebida). */
    private record CityCoverage(com.lucenadrive.geo.City city, List<List<com.lucenadrive.geo.GeoPoint>> polygons) {
    }

    private volatile List<CityCoverage> coverage = List.of();
    private volatile Instant coverageAt = Instant.EPOCH;

    private final DocStore store;
    private final RateLimiter limiter;
    private final AuditService audit;
    private final SchedulerLock lock;
    private final com.lucenadrive.geo.GeoService geo;
    private final Clock clock;

    public PresenceService(DocStore store, RateLimiter limiter, AuditService audit, SchedulerLock lock,
                           com.lucenadrive.geo.GeoService geo, Clock clock) {
        this.store = store;
        this.limiter = limiter;
        this.audit = audit;
        this.lock = lock;
        this.geo = geo;
        this.clock = clock;
    }

    public ConsentView consent(AuthUser user) {
        return store.get(Collections.LOCATION_CONSENTS, user.uid(), LocationConsent.class)
                .map(c -> new ConsentView(c.enabled(), c.version(), c.changedAt(), LocationConsent.CURRENT_VERSION))
                .orElse(new ConsentView(false, null, null, LocationConsent.CURRENT_VERSION));
    }

    public ConsentView setConsent(AuthUser user, boolean enabled) {
        Instant now = Instant.now(clock);
        store.transaction(tx -> {
            LocationConsent cur = tx.get(Collections.LOCATION_CONSENTS, user.uid(), LocationConsent.class).orElse(null);
            List<LocationConsent.Change> history = new ArrayList<>(cur == null || cur.history() == null
                    ? List.of() : cur.history());
            history.add(new LocationConsent.Change(enabled, LocationConsent.CURRENT_VERSION, now));
            if (history.size() > 50) {
                history = new ArrayList<>(history.subList(history.size() - 50, history.size()));
            }
            tx.set(Collections.LOCATION_CONSENTS, user.uid(),
                    new LocationConsent(user.uid(), enabled, LocationConsent.CURRENT_VERSION, now, history));
            if (!enabled) {
                tx.delete(Collections.PRESENCE, user.uid());
            }
            return null;
        });
        return consent(user);
    }

    /** Recebe a posição do app (no máximo 1 a cada 10 s). Exige consentimento ativo e papel compatível. */
    public void report(AuthUser user, Presence.Kind kind, double lat, double lng, Double accuracyM) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Coordenadas inválidas.");
        }
        if (!user.hasRole(kind == Presence.Kind.DRIVER ? Role.DRIVER : Role.PASSENGER)) {
            throw ApiException.forbidden("Perfil incompatível com o aplicativo.");
        }
        boolean consented = store.get(Collections.LOCATION_CONSENTS, user.uid(), LocationConsent.class)
                .map(LocationConsent::enabled).orElse(false);
        if (!consented) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Compartilhamento de localização desativado.");
        }
        if (!limiter.tryAcquire("presence:" + user.uid(), 1, 10_000)) {
            return; // envios excedentes são ignorados sem erro
        }
        User u = store.get(Collections.USERS, user.uid(), User.class)
                .filter(x -> x.status() == UserStatus.ACTIVE)
                .orElseThrow(() -> ApiException.forbidden("Conta indisponível."));
        Double acc = accuracyM == null ? null : Math.min(Math.max(accuracyM, 0), 100_000);
        com.lucenadrive.geo.City city = cityAt(new com.lucenadrive.geo.GeoPoint(lat, lng));
        store.set(Collections.PRESENCE, user.uid(), new Presence(user.uid(), u.fullName(), kind, lat, lng, acc,
                Instant.now(clock), com.lucenadrive.security.RequestContext.clientIp(),
                com.lucenadrive.security.RequestContext.userAgent(),
                city == null ? null : city.id(), city == null ? null : city.name() + " - " + city.state()));
    }

    /** Cidade atendida cuja área de cobertura contém o ponto (null = fora das áreas cadastradas). */
    private com.lucenadrive.geo.City cityAt(com.lucenadrive.geo.GeoPoint p) {
        Instant now = Instant.now(clock);
        if (coverageAt.plus(Duration.ofSeconds(60)).isBefore(now)) {
            List<CityCoverage> list = new ArrayList<>();
            for (com.lucenadrive.geo.City c : geo.activeCities()) {
                list.add(new CityCoverage(c, geo.activeAreas(c.id()).stream()
                        .filter(a -> a.type() == com.lucenadrive.geo.ServiceArea.AreaType.COVERAGE)
                        .map(com.lucenadrive.geo.ServiceArea::polygon).toList()));
            }
            coverage = list;
            coverageAt = now;
        }
        for (CityCoverage cc : coverage) {
            if (cc.polygons().stream().anyMatch(poly -> com.lucenadrive.geo.GeoMath.contains(poly, p))) {
                return cc.city();
            }
        }
        return null;
    }

    /** Resumo legível do aparelho a partir do User-Agent (navegador e sistema). */
    static String device(String ua) {
        if (ua == null || ua.isBlank()) {
            return null;
        }
        String os = ua.contains("Android") ? "Android" : ua.contains("iPhone") || ua.contains("iPad") ? "iOS"
                : ua.contains("Windows") ? "Windows" : ua.contains("Mac OS") ? "macOS" : ua.contains("Linux") ? "Linux" : "outro";
        String browser = ua.contains("Edg/") ? "Edge" : ua.contains("OPR/") ? "Opera" : ua.contains("SamsungBrowser") ? "Samsung Internet"
                : ua.contains("Chrome/") ? "Chrome" : ua.contains("Firefox/") ? "Firefox" : ua.contains("Safari/") ? "Safari" : "navegador";
        return browser + " · " + os;
    }

    /** Painel "O olho de Deus": posições recentes de quem consentiu. Auditado a cada 30 min por administrador. */
    public List<LivePosition> live(AuthUser staff, Presence.Kind kind, String cityId) {
        if (limiter.tryAcquire("godeye-audit:" + staff.uid(), 1, 1_800_000)) {
            audit.record(staff, "GOD_EYE_VIEWED", "presence", "all", Map.of(), Map.of(),
                    "Consulta ao painel O olho de Deus");
        }
        Query q = Query.of(Collections.PRESENCE).where("updatedAt", Query.Op.GTE, Instant.now(clock).minus(LIVE));
        return store.query(q.limit(2_000), Presence.class).stream()
                .filter(p -> kind == null || p.kind() == kind)
                .filter(p -> cityId == null || cityId.isBlank() || ("none".equals(cityId) ? p.cityId() == null : cityId.equals(p.cityId())))
                .map(p -> new LivePosition(p.userId(), p.name(), p.kind(), p.lat(), p.lng(), p.accuracyM(), p.updatedAt(),
                        p.ip(), device(p.userAgent()), p.cityId(), p.cityName()))
                .toList();
    }

    @Scheduled(fixedDelayString = "${lucena.presence.purge-ms:300000}")
    public void purge() {
        if (!lock.tryAcquire("presence-purge", Duration.ofMinutes(5))) {
            return;
        }
        Instant before = Instant.now(clock).minus(PURGE_AFTER);
        for (Presence p : store.query(Query.of(Collections.PRESENCE).where("updatedAt", Query.Op.LT, before).limit(500),
                Presence.class)) {
            store.delete(Collections.PRESENCE, p.userId());
        }
    }
}
