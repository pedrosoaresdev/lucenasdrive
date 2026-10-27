package com.lucenadrive.coupon;

import java.time.Instant;

/** couponRedemptions/{CODE}_{rideId}. RESERVED na solicitação, CONFIRMED na conclusão, RELEASED no cancelamento. */
public record CouponRedemption(String id, String code, String userId, String rideId, long discountCents,
                               Status status, Instant createdAt, Instant updatedAt) {

    public enum Status { RESERVED, CONFIRMED, RELEASED }
}
