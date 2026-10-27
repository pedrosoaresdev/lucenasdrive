package com.lucenadrive.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.coupon.Coupon;
import com.lucenadrive.coupon.CouponRules;
import com.lucenadrive.fare.VehicleCategory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CouponRulesTest {

    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    static Coupon coupon(Coupon.Type type, long pct, long amount, long maxDiscount, long minFare, long max, long perUser,
                         long count, long budget, long used, boolean newOnly) {
        return new Coupon("BEMVINDO10", "teste", type, pct, amount, maxDiscount, minFare, NOW.minusSeconds(3600),
                NOW.plusSeconds(3600), max, perUser, count, budget, used, List.of("c1"),
                List.of(VehicleCategory.ESSENCIAL), newOnly, List.of(), true, "admin", NOW, NOW);
    }

    static CouponRules.Context ctx(long total, long usage, boolean newPassenger) {
        return new CouponRules.Context("u1", "c1", VehicleCategory.ESSENCIAL, total, NOW, usage, newPassenger);
    }

    @Test
    void normalizesCodes() {
        assertThat(CouponRules.normalize(" bem-vindo 10 ")).contains("BEMVINDO10");
        assertThat(CouponRules.normalize("ab")).isEmpty();
        assertThat(CouponRules.normalize("CÓDIGO!")).isEmpty();
        assertThat(CouponRules.normalize("A".repeat(21))).isEmpty();
    }

    @Test
    void percentDiscountRespectsCap() {
        var eval = CouponRules.evaluate(coupon(Coupon.Type.PERCENT, 2_000, 0, 500, 0, 0, 0, 0, 0, 0, false), ctx(5_000, 0, false));
        assertThat(eval.valid()).isTrue();
        assertThat(eval.discountCents()).isEqualTo(500); // 20% de 50,00 = 10,00 → teto 5,00
    }

    @Test
    void fixedDiscountLimitedByRemainingBudget() {
        var eval = CouponRules.evaluate(coupon(Coupon.Type.FIXED, 0, 1_000, 0, 0, 0, 0, 0, 10_000, 9_700, false), ctx(5_000, 0, false));
        assertThat(eval.discountCents()).isEqualTo(300);
    }

    @Test
    void rejectsWhenExhaustedPerUserMinimumOrNotNewPassenger() {
        assertThat(CouponRules.evaluate(coupon(Coupon.Type.FIXED, 0, 500, 0, 0, 10, 0, 10, 0, 0, false), ctx(5_000, 0, false)).valid()).isFalse();
        assertThat(CouponRules.evaluate(coupon(Coupon.Type.FIXED, 0, 500, 0, 0, 0, 1, 0, 0, 0, false), ctx(5_000, 1, false)).valid()).isFalse();
        assertThat(CouponRules.evaluate(coupon(Coupon.Type.FIXED, 0, 500, 0, 6_000, 0, 0, 0, 0, 0, false), ctx(5_000, 0, false)).valid()).isFalse();
        assertThat(CouponRules.evaluate(coupon(Coupon.Type.FIXED, 0, 500, 0, 0, 0, 0, 0, 0, 0, true), ctx(5_000, 0, false)).valid()).isFalse();
        assertThat(CouponRules.evaluate(coupon(Coupon.Type.FIXED, 0, 500, 0, 0, 0, 0, 0, 0, 0, true), ctx(5_000, 0, true)).valid()).isTrue();
    }

    @Test
    void rejectsOtherCityOrCategoryAndExpired() {
        Coupon c = coupon(Coupon.Type.FIXED, 0, 500, 0, 0, 0, 0, 0, 0, 0, false);
        assertThat(CouponRules.evaluate(c, new CouponRules.Context("u1", "c2", VehicleCategory.ESSENCIAL, 5_000, NOW, 0, false)).valid()).isFalse();
        assertThat(CouponRules.evaluate(c, new CouponRules.Context("u1", "c1", VehicleCategory.EXECUTIVO, 5_000, NOW, 0, false)).valid()).isFalse();
        assertThat(CouponRules.evaluate(c, new CouponRules.Context("u1", "c1", VehicleCategory.ESSENCIAL, 5_000,
                NOW.plusSeconds(7200), 0, false)).rejection()).contains("expirado");
    }
}
