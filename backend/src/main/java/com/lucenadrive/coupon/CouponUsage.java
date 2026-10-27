package com.lucenadrive.coupon;

/** couponUsage/{CODE}_{uid}: contador por usuário (controle de limite por passageiro). */
public record CouponUsage(String id, String code, String userId, long count) {
}
