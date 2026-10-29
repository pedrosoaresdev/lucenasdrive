package com.lucenadrive.safety;

import com.lucenadrive.common.crypto.FieldEncryptor;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.ride.RideStatus;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.RateLimiter;
import com.lucenadrive.support.SupportService;
import com.lucenadrive.support.SupportTicket;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Recursos de segurança em viagem: compartilhamento do trajeto com um contato de confiança e alerta de
 * segurança para a equipe. Não há central de monitoramento 24 h: em emergência, a orientação é ligar 190/192.
 */
@Service
public class SafetyService {

    /** O link deixa de mostrar dados 1 h após o fim da corrida (ou 6 h após a criação, o que vier antes). */
    static final Duration AFTER_END = Duration.ofHours(1);
    static final Duration MAX_LIFETIME = Duration.ofHours(6);

    public record ShareLink(String token, Instant expiresAt) {
    }

    /** Visão pública mínima: sem sobrenome, telefone, CPF ou foto; posição só enquanto há motorista. */
    public record SharedRideView(String status, String passengerFirstName, String driverName, String vehicle,
                                 String vehicleColor, String plate, String originAddress, String destinationAddress,
                                 Ride.LivePosition driverPosition, Long etaSeconds, Instant updatedAt) {
    }

    private final DocStore store;
    private final SupportService support;
    private final RateLimiter limiter;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SafetyService(DocStore store, SupportService support, RateLimiter limiter, Clock clock) {
        this.store = store;
        this.support = support;
        this.limiter = limiter;
        this.clock = clock;
    }

    public ShareLink share(AuthUser passenger, String rideId) {
        Ride ride = store.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
        if (!passenger.uid().equals(ride.passengerId())) {
            throw ApiException.forbidden("Somente o passageiro pode compartilhar a viagem.");
        }
        if (!RideStatus.ACTIVE.contains(ride.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Só é possível compartilhar uma corrida em andamento.");
        }
        if (!limiter.tryAcquire("share:" + passenger.uid(), 10, 3_600_000)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Muitos links criados. Tente mais tarde.");
        }
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = Instant.now(clock);
        Instant expires = now.plus(MAX_LIFETIME);
        String id = FieldEncryptor.sha256Hex(token);
        store.set(Collections.RIDE_SHARES, id, new RideShare(id, rideId, passenger.uid(), false, now, expires));
        return new ShareLink(token, expires);
    }

    public void revokeAll(AuthUser passenger, String rideId) {
        for (RideShare s : store.query(Query.of(Collections.RIDE_SHARES).eq("rideId", rideId)
                .eq("passengerId", passenger.uid()).limit(50), RideShare.class)) {
            store.update(Collections.RIDE_SHARES, s.id(), Map.of("revoked", true));
        }
    }

    /** Consulta pública pelo link. Respostas idênticas para token inexistente, revogado ou expirado. */
    public SharedRideView view(String token, String clientKey) {
        if (!limiter.tryAcquire("shareview:" + clientKey, 120, 60_000)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Muitas consultas. Aguarde um instante.");
        }
        if (token == null || token.length() < 20 || token.length() > 64) {
            throw unavailable();
        }
        Instant now = Instant.now(clock);
        RideShare share = store.get(Collections.RIDE_SHARES, FieldEncryptor.sha256Hex(token), RideShare.class)
                .filter(s -> !s.revoked() && s.expiresAt().isAfter(now)).orElseThrow(SafetyService::unavailable);
        Ride r = store.get(Collections.RIDES, share.rideId(), Ride.class).orElseThrow(SafetyService::unavailable);
        Instant end = r.timeline().completedAt() != null ? r.timeline().completedAt() : r.timeline().cancelledAt();
        if (end != null && end.plus(AFTER_END).isBefore(now)) {
            throw unavailable();
        }
        boolean withDriver = RideStatus.WITH_DRIVER.contains(r.status());
        Ride.DriverSnapshot d = r.driver();
        String first = r.passenger() == null || r.passenger().name() == null ? "" : r.passenger().name().split("\\s+")[0];
        return new SharedRideView(r.status().name(), first,
                d == null ? null : d.name(), d == null ? null : d.vehicleMake() + " " + d.vehicleModel(),
                d == null ? null : d.vehicleColor(), d == null ? null : d.plate(),
                r.origin().address(), r.destination().address(),
                withDriver ? r.driverPosition() : null, withDriver ? r.etaSeconds() : null, r.updatedAt());
    }

    private static ApiException unavailable() {
        return new ApiException(ErrorCode.NOT_FOUND, "Link indisponível ou expirado.");
    }

    /**
     * Alerta de segurança: abre chamado URGENTE com a última posição conhecida. Não substitui 190/192.
     */
    public SupportTicket alert(AuthUser user, String rideId, String description) {
        Ride ride = store.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
        if (!ride.isParticipant(user.uid())) {
            throw ApiException.forbidden("Você não participa desta corrida.");
        }
        if (!limiter.tryAcquire("safety:" + user.uid(), 5, 600_000)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Alerta já enviado. A equipe foi avisada.");
        }
        Map<String, String> meta = new HashMap<>();
        meta.put("safetyAlert", "true");
        meta.put("rideStatus", ride.status().name());
        if (ride.driverPosition() != null) {
            meta.put("lastLat", String.valueOf(ride.driverPosition().lat()));
            meta.put("lastLng", String.valueOf(ride.driverPosition().lng()));
            meta.put("positionAt", String.valueOf(ride.driverPosition().updatedAt()));
        }
        String text = description == null || description.isBlank() ? "Alerta de segurança acionado no app." : description;
        return support.open(user, SupportTicket.Category.SAFETY, rideId, "ALERTA DE SEGURANÇA — corrida " + ride.code(),
                text, List.of(), meta);
    }
}
