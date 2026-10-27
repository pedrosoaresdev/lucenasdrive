package com.lucenadrive.coupon;

import com.lucenadrive.fare.VehicleCategory;
import java.time.Instant;
import java.util.List;

/** coupons/{CODE}: campanha promocional. O ID é o código normalizado. */
public record Coupon(
        String code,
        String description,
        Type type,
        long percentBp,
        long amountCents,
        long maxDiscountCents,
        long minFareCents,
        Instant startsAt,
        Instant endsAt,
        long maxRedemptions,
        long maxPerUser,
        long redemptionCount,
        long budgetCents,
        long budgetUsedCents,
        List<String> cityIds,
        List<VehicleCategory> categories,
        boolean newPassengersOnly,
        List<String> eligibleUserIds,
        boolean active,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public enum Type { PERCENT, FIXED }

    public Coupon withCounters(long count, long budgetUsed, Instant now) {
        return new Coupon(code, description, type, percentBp, amountCents, maxDiscountCents, minFareCents, startsAt,
                endsAt, maxRedemptions, maxPerUser, count, budgetCents, budgetUsed, cityIds, categories,
                newPassengersOnly, eligibleUserIds, active, createdBy, createdAt, now);
    }

    public Coupon withActive(boolean value, Instant now) {
        return new Coupon(code, description, type, percentBp, amountCents, maxDiscountCents, minFareCents, startsAt,
                endsAt, maxRedemptions, maxPerUser, redemptionCount, budgetCents, budgetUsedCents, cityIds, categories,
                newPassengersOnly, eligibleUserIds, value, createdBy, createdAt, now);
    }
}
