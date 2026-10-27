package com.lucenadrive.coupon;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.fare.VehicleCategory;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocMapper;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.persistence.Tx;
import com.lucenadrive.security.AuthUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Cupons validados exclusivamente no servidor. A reserva ocorre na mesma transação que cria a
 * corrida, garantindo que limites globais, por usuário e de orçamento não sejam ultrapassados
 * mesmo com solicitações concorrentes.
 */
@Service
public class CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

    public record CouponInput(String code, String description, Coupon.Type type, long percentBp, long amountCents,
                              long maxDiscountCents, long minFareCents, Instant startsAt, Instant endsAt,
                              long maxRedemptions, long maxPerUser, long budgetCents, List<String> cityIds,
                              List<VehicleCategory> categories, boolean newPassengersOnly,
                              List<String> eligibleUserIds, boolean active) {
    }

    public record Reservation(Coupon coupon, CouponUsage usage, long discountCents) {
    }

    public record CampaignResult(Coupon coupon, long confirmedRedemptions, long reservedRedemptions,
                                 long confirmedDiscountCents) {
    }

    private final DocStore store;
    private final AuditService audit;
    private final Clock clock;

    public CouponService(DocStore store, AuditService audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    public static String usageId(String code, String uid) {
        return code + "_" + uid;
    }

    public static String redemptionId(String code, String rideId) {
        return code + "_" + rideId;
    }

    /** Pré-visualização (sem reservar). */
    public CouponRules.Evaluation preview(String rawCode, CouponRules.Context ctx) {
        String code = CouponRules.normalize(rawCode).orElse(null);
        if (code == null) {
            return new CouponRules.Evaluation(0, "Código de cupom inválido.");
        }
        Optional<Coupon> coupon = store.get(Collections.COUPONS, code, Coupon.class);
        if (coupon.isEmpty()) {
            return new CouponRules.Evaluation(0, "Cupom não encontrado.");
        }
        long used = store.get(Collections.COUPON_USAGE, usageId(code, ctx.userId()), CouponUsage.class)
                .map(CouponUsage::count).orElse(0L);
        return CouponRules.evaluate(coupon.get(), withUsage(ctx, used));
    }

    /** Fase de leitura dentro da transação. Lança COUPON_INVALID se não aplicável. */
    public Reservation prepare(Tx tx, String rawCode, CouponRules.Context ctx) {
        String code = CouponRules.normalize(rawCode)
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_INVALID, "Código de cupom inválido."));
        Coupon coupon = tx.get(Collections.COUPONS, code, Coupon.class)
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_INVALID, "Cupom não encontrado."));
        CouponUsage usage = tx.get(Collections.COUPON_USAGE, usageId(code, ctx.userId()), CouponUsage.class)
                .orElse(new CouponUsage(usageId(code, ctx.userId()), code, ctx.userId(), 0));
        CouponRules.Evaluation eval = CouponRules.evaluate(coupon, withUsage(ctx, usage.count()));
        if (!eval.valid()) {
            throw new ApiException(ErrorCode.COUPON_INVALID, eval.rejection());
        }
        return new Reservation(coupon, usage, eval.discountCents());
    }

    /** Fase de escrita: incrementa contadores e registra a reserva. */
    public void apply(Tx tx, Reservation r, String rideId) {
        Instant now = Instant.now(clock);
        Coupon c = r.coupon();
        tx.set(Collections.COUPONS, c.code(),
                c.withCounters(c.redemptionCount() + 1, c.budgetUsedCents() + r.discountCents(), now));
        tx.set(Collections.COUPON_USAGE, r.usage().id(),
                new CouponUsage(r.usage().id(), c.code(), r.usage().userId(), r.usage().count() + 1));
        String id = redemptionId(c.code(), rideId);
        tx.create(Collections.COUPON_REDEMPTIONS, id, new CouponRedemption(id, c.code(), r.usage().userId(), rideId,
                r.discountCents(), CouponRedemption.Status.RESERVED, now, now));
    }

    /** Leituras para liberar uma reserva (corrida cancelada). */
    public record Release(CouponRedemption redemption, Coupon coupon, CouponUsage usage) {
    }

    public Optional<Release> prepareRelease(Tx tx, String code, String rideId) {
        Optional<CouponRedemption> red = tx.get(Collections.COUPON_REDEMPTIONS, redemptionId(code, rideId),
                CouponRedemption.class).filter(r -> r.status() == CouponRedemption.Status.RESERVED);
        if (red.isEmpty()) {
            return Optional.empty();
        }
        Coupon coupon = tx.get(Collections.COUPONS, code, Coupon.class).orElse(null);
        CouponUsage usage = tx.get(Collections.COUPON_USAGE, usageId(code, red.get().userId()), CouponUsage.class)
                .orElse(null);
        return Optional.of(new Release(red.get(), coupon, usage));
    }

    public void applyRelease(Tx tx, Release r) {
        Instant now = Instant.now(clock);
        CouponRedemption red = r.redemption();
        tx.set(Collections.COUPON_REDEMPTIONS, red.id(), new CouponRedemption(red.id(), red.code(), red.userId(),
                red.rideId(), red.discountCents(), CouponRedemption.Status.RELEASED, red.createdAt(), now));
        if (r.coupon() != null) {
            Coupon c = r.coupon();
            tx.set(Collections.COUPONS, c.code(), c.withCounters(Math.max(0, c.redemptionCount() - 1),
                    Math.max(0, c.budgetUsedCents() - red.discountCents()), now));
        }
        if (r.usage() != null) {
            tx.set(Collections.COUPON_USAGE, r.usage().id(), new CouponUsage(r.usage().id(), r.usage().code(),
                    r.usage().userId(), Math.max(0, r.usage().count() - 1)));
        }
    }

    public Optional<CouponRedemption> findRedemption(Tx tx, String code, String rideId) {
        return tx.get(Collections.COUPON_REDEMPTIONS, redemptionId(code, rideId), CouponRedemption.class);
    }

    public void confirm(Tx tx, CouponRedemption red) {
        tx.set(Collections.COUPON_REDEMPTIONS, red.id(), new CouponRedemption(red.id(), red.code(), red.userId(),
                red.rideId(), red.discountCents(), CouponRedemption.Status.CONFIRMED, red.createdAt(), Instant.now(clock)));
    }

    // ---------------- Administração ----------------

    public Coupon create(AuthUser admin, CouponInput in) {
        String code = CouponRules.normalize(in.code()).orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED,
                "Código deve ter de 4 a 20 letras ou números."));
        validate(in);
        Instant now = Instant.now(clock);
        Coupon c = toCoupon(code, in, 0, 0, admin.uid(), now, now);
        return store.transaction(tx -> {
            tx.create(Collections.COUPONS, code, c);
            audit.recordInTx(tx, admin, "COUPON_CREATED", "coupon", code, null, DocMapper.toMap(c), null);
            return c;
        });
    }

    public Coupon update(AuthUser admin, String code, CouponInput in, String reason) {
        validate(in);
        return store.transaction(tx -> {
            Coupon prev = tx.get(Collections.COUPONS, code, Coupon.class).orElseThrow(() -> ApiException.notFound("Cupom"));
            Coupon next = toCoupon(code, in, prev.redemptionCount(), prev.budgetUsedCents(), prev.createdBy(),
                    prev.createdAt(), Instant.now(clock));
            tx.set(Collections.COUPONS, code, next);
            audit.recordInTx(tx, admin, "COUPON_UPDATED", "coupon", code, DocMapper.toMap(prev), DocMapper.toMap(next), reason);
            return next;
        });
    }

    public Coupon setActive(AuthUser admin, String code, boolean active, String reason) {
        return store.transaction(tx -> {
            Coupon prev = tx.get(Collections.COUPONS, code, Coupon.class).orElseThrow(() -> ApiException.notFound("Cupom"));
            Coupon next = prev.withActive(active, Instant.now(clock));
            tx.set(Collections.COUPONS, code, next);
            audit.recordInTx(tx, admin, active ? "COUPON_ACTIVATED" : "COUPON_DEACTIVATED", "coupon", code,
                    Map.of("active", prev.active()), Map.of("active", active), reason);
            return next;
        });
    }

    public List<Coupon> list(Boolean active) {
        Query q = Query.of(Collections.COUPONS);
        if (active != null) {
            q.eq("active", active);
        }
        return store.query(q.orderBy("createdAt", Query.Direction.DESC).limit(200), Coupon.class);
    }

    public List<CouponRedemption> redemptions(String code) {
        return store.query(Query.of(Collections.COUPON_REDEMPTIONS).eq("code", code)
                .orderBy("createdAt", Query.Direction.DESC).limit(500), CouponRedemption.class);
    }

    public CampaignResult results(String code) {
        Coupon c = store.get(Collections.COUPONS, code, Coupon.class).orElseThrow(() -> ApiException.notFound("Cupom"));
        Query confirmed = Query.of(Collections.COUPON_REDEMPTIONS).eq("code", code)
                .eq("status", CouponRedemption.Status.CONFIRMED);
        Query reserved = Query.of(Collections.COUPON_REDEMPTIONS).eq("code", code)
                .eq("status", CouponRedemption.Status.RESERVED);
        return new CampaignResult(c, store.count(confirmed), store.count(reserved), store.sum(confirmed, "discountCents"));
    }

    /** Encerra automaticamente campanhas vencidas ou esgotadas. */
    @Scheduled(fixedDelayString = "${lucena.coupons.expire-check-ms:60000}")
    public void deactivateFinished() {
        if (!schedulerLock.tryAcquire("coupons", java.time.Duration.ofMinutes(10))) {
            return;
        }
        try {
            Instant now = Instant.now(clock);
            for (Coupon c : store.query(Query.of(Collections.COUPONS).eq("active", true), Coupon.class)) {
                boolean expired = c.endsAt() != null && !now.isBefore(c.endsAt());
                boolean exhausted = (c.maxRedemptions() > 0 && c.redemptionCount() >= c.maxRedemptions())
                        || (c.budgetCents() > 0 && c.budgetUsedCents() >= c.budgetCents());
                if (expired || exhausted) {
                    store.transaction(tx -> {
                        tx.get(Collections.COUPONS, c.code(), Coupon.class).ifPresent(cur ->
                                tx.set(Collections.COUPONS, c.code(), cur.withActive(false, now)));
                        return null;
                    });
                }
            }
        } catch (RuntimeException e) {
            log.warn("coupon_expiry_job_failed err={}", e.toString());
        }
    }

    private static CouponRules.Context withUsage(CouponRules.Context ctx, long used) {
        return new CouponRules.Context(ctx.userId(), ctx.cityId(), ctx.category(), ctx.totalCents(), ctx.now(), used,
                ctx.newPassenger());
    }

    static void validate(CouponInput in) {
        if (in.type() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tipo de desconto obrigatório.");
        }
        if (in.type() == Coupon.Type.PERCENT && (in.percentBp() <= 0 || in.percentBp() > 10_000)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Percentual deve estar entre 0,01% e 100%.");
        }
        if (in.type() == Coupon.Type.FIXED && in.amountCents() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Valor fixo deve ser positivo.");
        }
        if (in.maxDiscountCents() < 0 || in.minFareCents() < 0 || in.maxRedemptions() < 0 || in.maxPerUser() < 0
                || in.budgetCents() < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Limites não podem ser negativos.");
        }
        if (in.startsAt() != null && in.endsAt() != null && !in.endsAt().isAfter(in.startsAt())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Fim da validade deve ser posterior ao início.");
        }
    }

    private static Coupon toCoupon(String code, CouponInput in, long count, long budgetUsed, String createdBy,
                                   Instant createdAt, Instant now) {
        return new Coupon(code, in.description(), in.type(), in.percentBp(), in.amountCents(), in.maxDiscountCents(),
                in.minFareCents(), in.startsAt(), in.endsAt(), in.maxRedemptions(), in.maxPerUser(), count,
                in.budgetCents(), budgetUsed, in.cityIds() == null ? List.of() : in.cityIds(),
                in.categories() == null ? List.of() : in.categories(), in.newPassengersOnly(),
                in.eligibleUserIds() == null ? List.of() : in.eligibleUserIds(), in.active(), createdBy, createdAt, now);
    }

    private com.lucenadrive.scheduling.SchedulerLock schedulerLock;

    @org.springframework.beans.factory.annotation.Autowired
    void setSchedulerLock(com.lucenadrive.scheduling.SchedulerLock schedulerLock) {
        this.schedulerLock = schedulerLock;
    }
}
