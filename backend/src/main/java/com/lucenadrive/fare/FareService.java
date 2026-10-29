package com.lucenadrive.fare;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocMapper;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.security.AuthUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class FareService {

    /** Parâmetros editáveis pelo administrador. */
    public record FareConfigInput(long baseFareCents, long perKmCents, long perMinuteCents, long minimumFareCents,
                                  long cancellationFeeCents, long commissionBp, long demandMultiplierBp,
                                  long maxMultiplierBp, List<FareConfig.TimeWindow> timeWindows, boolean active) {
    }

    private final DocStore store;
    private final AuditService audit;
    private final Clock clock;

    public FareService(DocStore store, AuditService audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Tarifa vigente agora. Se houver alteração programada já vencida e o agendador ainda não a aplicou,
     * a nova versão é considerada imediatamente (mesmo número de versão que será gravado).
     */
    public Optional<FareConfig> current(String cityId, VehicleCategory category) {
        Instant now = Instant.now(clock);
        return store.get(Collections.FARE_CONFIGS, FareConfig.idFor(cityId, category), FareConfig.class)
                .map(c -> isDue(c, now) ? materialize(c, c.scheduled(), c.scheduled().effectiveFrom()) : c)
                .filter(FareConfig::active);
    }

    private static boolean isDue(FareConfig c, Instant now) {
        return c.scheduled() != null && !c.scheduled().effectiveFrom().isAfter(now);
    }

    private static FareConfig materialize(FareConfig prev, FareConfig.ScheduledChange s, Instant at) {
        FareConfigInput in = s.config();
        return new FareConfig(prev.id(), prev.cityId(), prev.category(), in.baseFareCents(), in.perKmCents(),
                in.perMinuteCents(), in.minimumFareCents(), in.cancellationFeeCents(), in.commissionBp(),
                in.demandMultiplierBp(), in.maxMultiplierBp(), in.timeWindows() == null ? List.of() : in.timeWindows(),
                in.active(), prev.version() + 1, at, s.scheduledBy(), at, null);
    }

    /** Programa uma alteração para o futuro (mín. 10 min, máx. 180 dias). Substitui agendamento anterior. */
    public FareConfig schedule(AuthUser admin, String cityId, VehicleCategory category, FareConfigInput in,
                               Instant effectiveFrom, String reason) {
        validate(in);
        Instant now = Instant.now(clock);
        if (effectiveFrom.isBefore(now.plus(java.time.Duration.ofMinutes(10)))
                || effectiveFrom.isAfter(now.plus(java.time.Duration.ofDays(180)))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A vigência deve começar entre 10 minutos e 180 dias a partir de agora.");
        }
        String id = FareConfig.idFor(cityId, category);
        return store.transaction(tx -> {
            FareConfig cur = tx.get(Collections.FARE_CONFIGS, id, FareConfig.class).orElseThrow(() -> new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Crie a tarifa vigente antes de programar uma alteração."));
            FareConfig.ScheduledChange s = new FareConfig.ScheduledChange(in, effectiveFrom, reason, admin.uid(), now);
            FareConfig next = withScheduled(cur, s);
            tx.set(Collections.FARE_CONFIGS, id, next);
            audit.recordInTx(tx, admin, "FARE_CONFIG_SCHEDULED", "fareConfig", id,
                    cur.scheduled() == null ? null : DocMapper.toMap(cur.scheduled()), DocMapper.toMap(s), reason);
            return next;
        });
    }

    public FareConfig cancelScheduled(AuthUser admin, String cityId, VehicleCategory category, String reason) {
        String id = FareConfig.idFor(cityId, category);
        return store.transaction(tx -> {
            FareConfig cur = tx.get(Collections.FARE_CONFIGS, id, FareConfig.class)
                    .orElseThrow(() -> ApiException.notFound("Tarifa"));
            if (cur.scheduled() == null) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Não há alteração programada.");
            }
            FareConfig next = withScheduled(cur, null);
            tx.set(Collections.FARE_CONFIGS, id, next);
            audit.recordInTx(tx, admin, "FARE_CONFIG_SCHEDULE_CANCELLED", "fareConfig", id,
                    DocMapper.toMap(cur.scheduled()), null, reason);
            return next;
        });
    }

    private static FareConfig withScheduled(FareConfig c, FareConfig.ScheduledChange s) {
        return new FareConfig(c.id(), c.cityId(), c.category(), c.baseFareCents(), c.perKmCents(), c.perMinuteCents(),
                c.minimumFareCents(), c.cancellationFeeCents(), c.commissionBp(), c.demandMultiplierBp(),
                c.maxMultiplierBp(), c.timeWindows(), c.active(), c.version(), c.effectiveFrom(), c.updatedBy(),
                c.updatedAt(), s);
    }

    /** Aplica as alterações programadas vencidas, criando a nova versão com a data de vigência. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${lucena.fares.schedule-check-ms:60000}")
    public void applyDueSchedules() {
        if (schedulerLock != null && !schedulerLock.tryAcquire("fare-schedules", java.time.Duration.ofMinutes(2))) {
            return;
        }
        Instant now = Instant.now(clock);
        for (FareConfig c : store.query(Query.of(Collections.FARE_CONFIGS)
                .where("scheduled.effectiveFrom", Query.Op.LTE, now).limit(100), FareConfig.class)) {
            try {
                store.transaction(tx -> {
                    FareConfig cur = tx.get(Collections.FARE_CONFIGS, c.id(), FareConfig.class).orElse(null);
                    if (cur == null || !isDue(cur, now)) {
                        return null;
                    }
                    FareConfig.ScheduledChange s = cur.scheduled();
                    FareConfig next = materialize(cur, s, s.effectiveFrom());
                    String versionId = cur.id() + "_v" + next.version();
                    tx.create(Collections.FARE_CONFIG_VERSIONS, versionId, new FareConfigVersion(versionId, cur.id(),
                            next.version(), next, withScheduled(cur, null), s.scheduledBy(), now, s.reason(),
                            s.effectiveFrom()));
                    tx.set(Collections.FARE_CONFIGS, cur.id(), next);
                    return null;
                });
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(FareService.class).warn("fare_schedule_apply_failed id={} err={}",
                        c.id(), e.toString());
            }
        }
    }

    private com.lucenadrive.scheduling.SchedulerLock schedulerLock;

    @org.springframework.beans.factory.annotation.Autowired
    void setSchedulerLock(com.lucenadrive.scheduling.SchedulerLock schedulerLock) {
        this.schedulerLock = schedulerLock;
    }

    public FareConfig requireCurrent(String cityId, VehicleCategory category) {
        return current(cityId, category).orElseThrow(() -> new ApiException(ErrorCode.FARE_NOT_CONFIGURED,
                "Categoria " + category.displayName() + " indisponível nesta cidade."));
    }

    public List<FareConfig> listByCity(String cityId) {
        return store.query(Query.of(Collections.FARE_CONFIGS).eq("cityId", cityId), FareConfig.class);
    }

    public List<FareConfigVersion> history(String configId) {
        return store.query(Query.of(Collections.FARE_CONFIG_VERSIONS).eq("configId", configId)
                .orderBy("version", Query.Direction.DESC).limit(100), FareConfigVersion.class);
    }

    /**
     * Cria nova versão da tarifa. A versão anterior é preservada; corridas já criadas mantêm
     * a composição de preço gravada nelas e não são afetadas.
     */
    public FareConfig upsert(AuthUser admin, String cityId, VehicleCategory category, FareConfigInput in, String reason) {
        validate(in);
        String id = FareConfig.idFor(cityId, category);
        return store.transaction(tx -> {
            if (tx.get(Collections.CITIES, cityId, com.lucenadrive.geo.City.class).isEmpty()) {
                throw ApiException.notFound("Cidade");
            }
            Optional<FareConfig> previous = tx.get(Collections.FARE_CONFIGS, id, FareConfig.class);
            Instant now = Instant.now(clock);
            int version = previous.map(p -> p.version() + 1).orElse(1);
            FareConfig next = new FareConfig(id, cityId, category, in.baseFareCents(), in.perKmCents(),
                    in.perMinuteCents(), in.minimumFareCents(), in.cancellationFeeCents(), in.commissionBp(),
                    in.demandMultiplierBp(), in.maxMultiplierBp(),
                    in.timeWindows() == null ? List.of() : in.timeWindows(), in.active(), version, now, admin.uid(), now,
                    previous.map(FareConfig::scheduled).orElse(null));
            String versionId = id + "_v" + version;
            tx.create(Collections.FARE_CONFIG_VERSIONS, versionId,
                    new FareConfigVersion(versionId, id, version, next, previous.orElse(null), admin.uid(), now, reason, now));
            tx.set(Collections.FARE_CONFIGS, id, next);
            audit.recordInTx(tx, admin, "FARE_CONFIG_UPDATED", "fareConfig", id,
                    previous.map(DocMapper::toMap).orElse(null), DocMapper.toMap(next), reason);
            return next;
        });
    }

    static void validate(FareConfigInput in) {
        if (in.baseFareCents() < 0 || in.perKmCents() < 0 || in.perMinuteCents() < 0
                || in.minimumFareCents() < 0 || in.cancellationFeeCents() < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Valores tarifários não podem ser negativos.");
        }
        if (in.commissionBp() < 0 || in.commissionBp() > 5_000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Comissão deve estar entre 0% e 50%.");
        }
        if (in.demandMultiplierBp() < 10_000 || in.maxMultiplierBp() < 10_000 || in.maxMultiplierBp() > 30_000
                || in.demandMultiplierBp() > in.maxMultiplierBp()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Multiplicadores devem estar entre 1,0× e 3,0× e o de demanda não pode superar o teto.");
        }
        if (in.timeWindows() != null) {
            for (FareConfig.TimeWindow w : in.timeWindows()) {
                if (w.multiplierBp() < 10_000 || w.multiplierBp() > in.maxMultiplierBp()
                        || !w.start().matches("\\d{2}:\\d{2}") || !w.end().matches("\\d{2}:\\d{2}")
                        || w.daysOfWeek() == null || w.daysOfWeek().stream().anyMatch(d -> d < 1 || d > 7)) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Janela horária inválida.");
                }
            }
        }
    }
}
