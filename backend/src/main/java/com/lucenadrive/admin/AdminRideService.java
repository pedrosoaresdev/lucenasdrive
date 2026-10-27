package com.lucenadrive.admin;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.common.web.PageResponse;
import com.lucenadrive.ledger.LedgerEntry;
import com.lucenadrive.ledger.LedgerEntryType;
import com.lucenadrive.ledger.LedgerService;
import com.lucenadrive.ledger.LedgerStatus;
import com.lucenadrive.payment.PaymentTransaction;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.rating.Rating;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.ride.RideEvent;
import com.lucenadrive.ride.RideEventService;
import com.lucenadrive.ride.RideStatus;
import com.lucenadrive.ride.RideView;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.Role;
import com.lucenadrive.security.SecurityGuards;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AdminRideService {

    public record RideFilter(RideStatus status, String cityId, String driverId, String passengerId, Instant from,
                             Instant to) {
    }

    public record RideDetail(RideView ride, List<RideEvent> events, List<LedgerEntry> ledger,
                             List<PaymentTransaction> payments, Rating rating,
                             Rating passengerRating) {
    }

    public record LiveRide(String id, String code, RideStatus status, String cityId, double originLat, double originLng,
                           double destinationLat, double destinationLng, Ride.LivePosition driverPosition,
                           String driverName, String plate) {
    }

    private final DocStore store;
    private final RideEventService events;
    private final LedgerService ledger;
    private final AuditService audit;
    private final SecurityGuards guards;
    private final com.lucenadrive.ride.RideService rides;

    public AdminRideService(DocStore store, RideEventService events, LedgerService ledger, AuditService audit,
                            SecurityGuards guards, com.lucenadrive.ride.RideService rides) {
        this.store = store;
        this.events = events;
        this.ledger = ledger;
        this.audit = audit;
        this.guards = guards;
        this.rides = rides;
    }

    public RideView cancel(AuthUser staff, String rideId, String reason) {
        RideView v = rides.cancelByStaff(staff, rideId, reason);
        audit.record(staff, "RIDE_CANCELLED_BY_STAFF", "ride", rideId, null, Map.of("status", "CANCELLED"), reason);
        return v;
    }

    public PageResponse<RideView> list(RideFilter f, Long cursor, int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        Query q = Query.of(Collections.RIDES);
        if (f.status() != null) {
            q.eq("status", f.status());
        }
        if (f.cityId() != null) {
            q.eq("cityId", f.cityId());
        }
        if (f.driverId() != null) {
            q.eq("driverId", f.driverId());
        }
        if (f.passengerId() != null) {
            q.eq("passengerId", f.passengerId());
        }
        if (f.from() != null) {
            q.where("createdAt", Query.Op.GTE, f.from());
        }
        if (f.to() != null) {
            q.where("createdAt", Query.Op.LT, f.to());
        }
        q.orderBy("createdAt", Query.Direction.DESC).limit(size);
        if (cursor != null) {
            q.startAfter(cursor);
        }
        List<RideView> items = store.query(q, Ride.class).stream().map(r -> RideView.of(r, RideView.Viewer.STAFF)).toList();
        return PageResponse.of(items, size, v -> v.createdAt().toEpochMilli());
    }

    public RideDetail detail(String rideId) {
        Ride r = store.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
        return new RideDetail(RideView.of(r, RideView.Viewer.STAFF), events.list(rideId), ledger.byRide(rideId),
                store.query(Query.of(Collections.PAYMENT_TRANSACTIONS).eq("rideId", rideId), PaymentTransaction.class),
                store.get(Collections.RATINGS, Rating.idFor(rideId, Rating.RaterRole.PASSENGER), Rating.class).orElse(null),
                store.get(Collections.RATINGS, Rating.idFor(rideId, Rating.RaterRole.DRIVER), Rating.class).orElse(null));
    }

    /** Corridas ativas para o mapa operacional (sem dados pessoais além do primeiro nome). */
    public List<LiveRide> live(String cityId) {
        Query q = Query.of(Collections.RIDES).where("status", Query.Op.IN, RideStatus.ACTIVE);
        if (cityId != null) {
            q.eq("cityId", cityId);
        }
        return store.query(q.limit(500), Ride.class).stream()
                .map(r -> new LiveRide(r.id(), r.code(), r.status(), r.cityId(), r.origin().lat(), r.origin().lng(),
                        r.destination().lat(), r.destination().lng(), r.driverPosition(),
                        r.driver() == null ? null : r.driver().name(), r.driver() == null ? null : r.driver().plate()))
                .toList();
    }

    /**
     * Ajuste financeiro: cria um NOVO lançamento (ADJUSTMENT) vinculado à corrida. Os lançamentos
     * originais permanecem intactos. Exige papel financeiro/administrativo e MFA recente.
     */
    public LedgerEntry adjust(AuthUser staff, String rideId, String account, LedgerEntry.Direction direction,
                              long amountCents, String reason, String idempotencyKey) {
        guards.requireAnyRole(staff, Role.FINANCE, Role.ADMIN, Role.OWNER);
        guards.requireRecentMfa(staff);
        if (reason == null || reason.trim().length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Motivo obrigatório (mín. 10 caracteres).");
        }
        if (amountCents <= 0 || amountCents > 1_000_000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Valor do ajuste inválido.");
        }
        if (idempotencyKey == null || !idempotencyKey.matches("^[A-Za-z0-9-]{8,64}$")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cabeçalho Idempotency-Key obrigatório.");
        }
        Ride ride = store.get(Collections.RIDES, rideId, Ride.class).orElseThrow(() -> ApiException.notFound("Corrida"));
        String acc = switch (account) {
            case "PLATFORM" -> LedgerEntry.PLATFORM;
            case "DRIVER" -> {
                if (ride.driverId() == null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Corrida sem motorista.");
                }
                yield LedgerEntry.driverAccount(ride.driverId());
            }
            default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Conta inválida.");
        };
        String key = "adjustment:" + rideId + ":" + idempotencyKey;
        return store.transaction(tx -> {
            Optional<LedgerEntry> existing = tx.get(Collections.LEDGER_ENTRIES, LedgerService.idFor(key), LedgerEntry.class);
            if (existing.isPresent()) {
                return existing.get();
            }
            LedgerEntry e = ledger.write(tx, new LedgerService.Draft(LedgerEntryType.ADJUSTMENT, acc, direction, amountCents,
                    rideId, acc.startsWith("DRIVER:") ? ride.driverId() : null, null, null,
                    LedgerStatus.AWAITING_SETTLEMENT, key, null, reason));
            audit.recordInTx(tx, staff, "RIDE_FINANCIAL_ADJUSTMENT", "ride", rideId, null,
                    Map.of("ledgerEntryId", e.id(), "account", acc, "direction", direction.name(), "amountCents", amountCents),
                    reason);
            return e;
        });
    }
}
