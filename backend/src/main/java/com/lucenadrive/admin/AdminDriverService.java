package com.lucenadrive.admin;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.driver.DocumentType;
import com.lucenadrive.driver.DriverDocument;
import com.lucenadrive.driver.DriverLocation;
import com.lucenadrive.driver.DriverProfile;
import com.lucenadrive.driver.DriverStatus;
import com.lucenadrive.driver.Vehicle;
import com.lucenadrive.integration.ObjectStorage;
import com.lucenadrive.notification.NotificationService;
import com.lucenadrive.notification.NotificationType;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.user.IdentityRegistrationService;
import com.lucenadrive.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Aprovação e gestão de motoristas, documentos e veículos. Toda decisão exige motivo e é auditada. */
@Service
public class AdminDriverService {

    private static final Logger log = LoggerFactory.getLogger(AdminDriverService.class);

    public record DriverDetail(User user, DriverProfile profile, List<Vehicle> vehicles, DriverLocation location,
                               List<Ride> recentRides) {
    }

    /** Transições administrativas permitidas para o status do motorista. */
    private static final Map<DriverStatus, Set<DriverStatus>> ALLOWED = Map.of(
            DriverStatus.PENDING_REVIEW, EnumSet.of(DriverStatus.APPROVED, DriverStatus.REJECTED),
            DriverStatus.APPROVED, EnumSet.of(DriverStatus.SUSPENDED, DriverStatus.DOCUMENTS_EXPIRED),
            DriverStatus.SUSPENDED, EnumSet.of(DriverStatus.APPROVED),
            DriverStatus.REJECTED, EnumSet.of(DriverStatus.PENDING_REVIEW),
            DriverStatus.DOCUMENTS_EXPIRED, EnumSet.of(DriverStatus.APPROVED, DriverStatus.SUSPENDED),
            DriverStatus.INCOMPLETE, EnumSet.noneOf(DriverStatus.class));

    private final DocStore store;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ObjectProvider<ObjectStorage> storage;
    private final Clock clock;

    public AdminDriverService(DocStore store, AuditService audit, NotificationService notifications,
                              ObjectProvider<ObjectStorage> storage, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.notifications = notifications;
        this.storage = storage;
        this.clock = clock;
    }

    public List<DriverProfile> list(DriverStatus status, String cityId, int limit) {
        Query q = Query.of(Collections.DRIVER_PROFILES);
        if (status != null) {
            q.eq("status", status);
        }
        if (cityId != null) {
            q.eq("cityId", cityId);
        }
        return store.query(q.orderBy("updatedAt", Query.Direction.DESC).limit(Math.min(limit, 200)), DriverProfile.class);
    }

    public DriverDetail detail(String driverId) {
        DriverProfile p = store.get(Collections.DRIVER_PROFILES, driverId, DriverProfile.class)
                .orElseThrow(() -> ApiException.notFound("Motorista"));
        return new DriverDetail(store.get(Collections.USERS, driverId, User.class).orElse(null), p,
                store.query(Query.of(Collections.VEHICLES).eq("driverId", driverId), Vehicle.class),
                store.get(Collections.DRIVER_LOCATIONS, driverId, DriverLocation.class).orElse(null),
                store.query(Query.of(Collections.RIDES).eq("driverId", driverId)
                        .orderBy("createdAt", Query.Direction.DESC).limit(20), Ride.class));
    }

    public DriverProfile changeStatus(AuthUser staff, String driverId, DriverStatus target, String reason) {
        requireReason(reason);
        DriverProfile updated = store.transaction(tx -> {
            DriverProfile p = tx.get(Collections.DRIVER_PROFILES, driverId, DriverProfile.class)
                    .orElseThrow(() -> ApiException.notFound("Motorista"));
            if (!ALLOWED.getOrDefault(p.status(), Set.of()).contains(target)) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                        "Não é possível mudar de " + p.status() + " para " + target + ".");
            }
            if (target == DriverStatus.APPROVED) {
                boolean docsOk = p.documents() != null && Set.of(DocumentType.values()).stream()
                        .filter(DocumentType::requiredForDriver)
                        .allMatch(t -> p.documents().stream().anyMatch(d -> d.type() == t
                                && d.status() == DriverDocument.Status.APPROVED));
                if (!docsOk) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Aprove os documentos obrigatórios antes do motorista.");
                }
            }
            DriverLocation loc = tx.get(Collections.DRIVER_LOCATIONS, driverId, DriverLocation.class).orElse(null);
            Instant now = Instant.now(clock);
            DriverProfile next = p.toBuilder().status(target, reason).reviewed(staff.uid(), now).updatedAt(now).build();
            tx.set(Collections.DRIVER_PROFILES, driverId, next);
            if (target != DriverStatus.APPROVED && loc != null && loc.online() && loc.currentRideId() == null) {
                tx.set(Collections.DRIVER_LOCATIONS, driverId, loc.withOnline(false, now));
            }
            audit.recordInTx(tx, staff, "DRIVER_STATUS_" + target.name(), "driver", driverId,
                    Map.of("status", p.status().name()), Map.of("status", target.name()), reason);
            return next;
        });
        String msg = switch (target) {
            case APPROVED -> "Seu cadastro foi aprovado! Você já pode ficar online.";
            case REJECTED -> "Seu cadastro não foi aprovado. Motivo: " + reason;
            case SUSPENDED -> "Sua conta de motorista foi suspensa. Motivo: " + reason;
            case DOCUMENTS_EXPIRED -> "Há documentos vencidos. Atualize para voltar a dirigir.";
            default -> "O status do seu cadastro foi atualizado.";
        };
        notifications.notify(driverId, target == DriverStatus.APPROVED ? NotificationType.DRIVER_APPROVED
                : NotificationType.DRIVER_STATUS_CHANGED, "Cadastro de motorista", msg, Map.of());
        return updated;
    }

    public DriverProfile reviewDocument(AuthUser staff, String driverId, DocumentType type, String vehicleId,
                                        boolean approve, String note) {
        if (!approve) {
            requireReason(note);
        }
        DriverProfile updated = store.transaction(tx -> {
            DriverProfile p = tx.get(Collections.DRIVER_PROFILES, driverId, DriverProfile.class)
                    .orElseThrow(() -> ApiException.notFound("Motorista"));
            List<DriverDocument> docs = new ArrayList<>(p.documents() == null ? List.of() : p.documents());
            Instant now = Instant.now(clock);
            boolean found = false;
            for (int i = 0; i < docs.size(); i++) {
                DriverDocument d = docs.get(i);
                if (d.type() == type && Objects.equals(d.vehicleId(), vehicleId)) {
                    docs.set(i, d.reviewed(approve ? DriverDocument.Status.APPROVED : DriverDocument.Status.REJECTED,
                            staff.uid(), now, note));
                    found = true;
                }
            }
            if (!found) {
                throw ApiException.notFound("Documento");
            }
            DriverProfile next = p.toBuilder().documents(docs).updatedAt(now).build();
            tx.set(Collections.DRIVER_PROFILES, driverId, next);
            audit.recordInTx(tx, staff, approve ? "DRIVER_DOCUMENT_APPROVED" : "DRIVER_DOCUMENT_REJECTED", "driver",
                    driverId, null, Map.of("document", type.name()), note);
            return next;
        });
        notifications.notify(driverId, NotificationType.DOCUMENT_REVIEWED, "Documento analisado",
                approve ? "Seu documento foi aprovado." : "Seu documento foi recusado: " + note, Map.of());
        return updated;
    }

    /** URL temporária (5 min) para visualizar um documento privado. Acesso auditado. */
    public Map<String, String> documentUrl(AuthUser staff, String driverId, DocumentType type, String vehicleId) {
        DriverProfile p = store.get(Collections.DRIVER_PROFILES, driverId, DriverProfile.class)
                .orElseThrow(() -> ApiException.notFound("Motorista"));
        DriverDocument doc = p.documents().stream()
                .filter(d -> d.type() == type && Objects.equals(d.vehicleId(), vehicleId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Documento"));
        ObjectStorage os = storage.getIfAvailable();
        if (os == null) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED, "Armazenamento indisponível.");
        }
        String url = os.signedReadUrl(doc.storagePath(), Duration.ofMinutes(5));
        audit.record(staff, "DRIVER_DOCUMENT_VIEWED", "driver", driverId, null, Map.of("document", type.name()), null);
        return Map.of("url", url, "contentType", String.valueOf(doc.contentType()));
    }

    public Vehicle changeVehicleStatus(AuthUser staff, String vehicleId, Vehicle.Status status, String reason) {
        if (status != Vehicle.Status.APPROVED) {
            requireReason(reason);
        }
        Vehicle updated = store.transaction(tx -> {
            Vehicle v = tx.get(Collections.VEHICLES, vehicleId, Vehicle.class).orElseThrow(() -> ApiException.notFound("Veículo"));
            Vehicle next = v.withStatus(status, reason, Instant.now(clock));
            tx.set(Collections.VEHICLES, vehicleId, next);
            audit.recordInTx(tx, staff, "VEHICLE_" + status.name(), "vehicle", vehicleId,
                    Map.of("status", v.status().name()), Map.of("status", status.name()), reason);
            return next;
        });
        notifications.notify(updated.driverId(), NotificationType.DOCUMENT_REVIEWED, "Veículo analisado",
                "Status do veículo " + updated.displayPlate() + ": " + status, Map.of());
        return updated;
    }

    public DriverProfile warn(AuthUser staff, String driverId, String reason) {
        requireReason(reason);
        DriverProfile updated = store.transaction(tx -> {
            DriverProfile p = tx.get(Collections.DRIVER_PROFILES, driverId, DriverProfile.class)
                    .orElseThrow(() -> ApiException.notFound("Motorista"));
            DriverProfile next = p.toBuilder().warnings(p.warnings() + 1).updatedAt(Instant.now(clock)).build();
            tx.set(Collections.DRIVER_PROFILES, driverId, next);
            audit.recordInTx(tx, staff, "DRIVER_WARNING", "driver", driverId,
                    Map.of("warnings", p.warnings()), Map.of("warnings", next.warnings()), reason);
            return next;
        });
        notifications.notify(driverId, NotificationType.DRIVER_STATUS_CHANGED, "Advertência registrada", reason, Map.of());
        return updated;
    }

    /** Marca como DOCUMENTS_EXPIRED motoristas aprovados com CNH vencida (diário). */
    @Scheduled(cron = "${lucena.drivers.expiry-cron:0 15 3 * * *}", zone = "America/Sao_Paulo")
    public void expireDocuments() {
        if (!schedulerLock.tryAcquire("driver-documents", java.time.Duration.ofMinutes(10))) {
            return;
        }
        try {
            LocalDate today = LocalDate.now(clock.withZone(IdentityRegistrationService.BR));
            for (DriverProfile p : store.query(Query.of(Collections.DRIVER_PROFILES).eq("status", DriverStatus.APPROVED)
                    .limit(5000), DriverProfile.class)) {
                if (p.cnhExpiresOn() != null && LocalDate.parse(p.cnhExpiresOn()).isBefore(today)) {
                    store.transaction(tx -> {
                        tx.get(Collections.DRIVER_PROFILES, p.id(), DriverProfile.class).ifPresent(cur ->
                                tx.set(Collections.DRIVER_PROFILES, cur.id(), cur.toBuilder()
                                        .status(DriverStatus.DOCUMENTS_EXPIRED, "CNH vencida")
                                        .updatedAt(Instant.now(clock)).build()));
                        return null;
                    });
                    notifications.notify(p.id(), NotificationType.DRIVER_STATUS_CHANGED, "CNH vencida",
                            "Atualize sua CNH para voltar a receber corridas.", Map.of());
                }
            }
        } catch (RuntimeException e) {
            log.warn("document_expiry_job_failed err={}", e.toString());
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Motivo obrigatório.");
        }
    }

    private com.lucenadrive.scheduling.SchedulerLock schedulerLock;

    @org.springframework.beans.factory.annotation.Autowired
    void setSchedulerLock(com.lucenadrive.scheduling.SchedulerLock schedulerLock) {
        this.schedulerLock = schedulerLock;
    }
}
