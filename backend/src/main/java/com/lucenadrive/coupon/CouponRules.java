package com.lucenadrive.coupon;

import com.lucenadrive.common.money.Money;
import com.lucenadrive.fare.VehicleCategory;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Regras puras de cupom (normalização e elegibilidade). */
public final class CouponRules {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9]{4,20}$");

    public record Context(String userId, String cityId, VehicleCategory category, long totalCents, Instant now,
                          long userUsageCount, boolean newPassenger) {
    }

    /** Resultado: desconto em centavos, ou motivo de recusa. */
    public record Evaluation(long discountCents, String rejection) {
        public boolean valid() {
            return rejection == null;
        }
    }

    private CouponRules() {
    }

    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String code = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        return CODE.matcher(code).matches() ? Optional.of(code) : Optional.empty();
    }

    public static Evaluation evaluate(Coupon c, Context ctx) {
        if (!c.active()) {
            return reject("Cupom inativo.");
        }
        if (c.startsAt() != null && ctx.now().isBefore(c.startsAt())) {
            return reject("Cupom ainda não está válido.");
        }
        if (c.endsAt() != null && !ctx.now().isBefore(c.endsAt())) {
            return reject("Cupom expirado.");
        }
        if (c.maxRedemptions() > 0 && c.redemptionCount() >= c.maxRedemptions()) {
            return reject("Cupom esgotado.");
        }
        if (c.maxPerUser() > 0 && ctx.userUsageCount() >= c.maxPerUser()) {
            return reject("Você já atingiu o limite de uso deste cupom.");
        }
        if (c.cityIds() != null && !c.cityIds().isEmpty() && !c.cityIds().contains(ctx.cityId())) {
            return reject("Cupom não válido nesta cidade.");
        }
        if (c.categories() != null && !c.categories().isEmpty() && !c.categories().contains(ctx.category())) {
            return reject("Cupom não válido para esta categoria.");
        }
        if (c.newPassengersOnly() && !ctx.newPassenger()) {
            return reject("Cupom exclusivo para a primeira viagem.");
        }
        if (c.eligibleUserIds() != null && !c.eligibleUserIds().isEmpty() && !c.eligibleUserIds().contains(ctx.userId())) {
            return reject("Cupom não disponível para sua conta.");
        }
        if (ctx.totalCents() < c.minFareCents()) {
            return reject("Valor mínimo da corrida para este cupom: " + Money.format(c.minFareCents()) + ".");
        }
        long discount = c.type() == Coupon.Type.PERCENT
                ? Money.applyBasisPoints(ctx.totalCents(), c.percentBp())
                : c.amountCents();
        if (c.maxDiscountCents() > 0) {
            discount = Math.min(discount, c.maxDiscountCents());
        }
        discount = Math.min(discount, ctx.totalCents());
        if (c.budgetCents() > 0) {
            long remaining = c.budgetCents() - c.budgetUsedCents();
            if (remaining <= 0) {
                return reject("Orçamento da campanha esgotado.");
            }
            discount = Math.min(discount, remaining);
        }
        if (discount <= 0) {
            return reject("Cupom sem desconto aplicável.");
        }
        return new Evaluation(discount, null);
    }

    private static Evaluation reject(String reason) {
        return new Evaluation(0, reason);
    }
}
