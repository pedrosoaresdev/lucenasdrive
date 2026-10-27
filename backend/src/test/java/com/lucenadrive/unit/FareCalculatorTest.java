package com.lucenadrive.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lucenadrive.common.money.Money;
import com.lucenadrive.fare.FareBreakdown;
import com.lucenadrive.fare.FareCalculator;
import com.lucenadrive.fare.FareConfig;
import com.lucenadrive.fare.VehicleCategory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class FareCalculatorTest {

    static FareConfig config(long minimum, long demandBp, long maxBp, List<FareConfig.TimeWindow> windows) {
        // base R$ 5,00; R$ 2,00/km; R$ 0,30/min; comissão 20%
        return new FareConfig("c_ESSENCIAL", "c", VehicleCategory.ESSENCIAL, 500, 200, 30, minimum, 700, 2000,
                demandBp, maxBp, windows, true, 3, Instant.EPOCH, "admin", Instant.EPOCH);
    }

    @Test
    void computesBaseDistanceTimeAndCommissionInCents() {
        FareBreakdown f = FareCalculator.calculate(config(800, 10_000, 20_000, List.of()), 10_000, 1_200, 10_000, 0);
        assertThat(f.distanceCents()).isEqualTo(2_000);
        assertThat(f.timeCents()).isEqualTo(600);
        assertThat(f.subtotalCents()).isEqualTo(3_100);
        assertThat(f.totalCents()).isEqualTo(3_100);
        assertThat(f.commissionCents()).isEqualTo(620);
        assertThat(f.driverEarningCents()).isEqualTo(2_480);
        assertThat(f.finalCents()).isEqualTo(3_100);
        assertThat(f.fareConfigVersion()).isEqualTo(3);
    }

    @Test
    void appliesMinimumFare() {
        FareBreakdown f = FareCalculator.calculate(config(1_000, 10_000, 20_000, List.of()), 500, 60, 10_000, 0);
        assertThat(f.minimumApplied()).isTrue();
        assertThat(f.totalCents()).isEqualTo(1_000);
    }

    @Test
    void roundsHalfUpOnFractionalCents() {
        // 1.234 m × 200 = 246,8 → 247 centavos
        FareBreakdown f = FareCalculator.calculate(config(0, 10_000, 20_000, List.of()), 1_234, 0, 10_000, 0);
        assertThat(f.distanceCents()).isEqualTo(247);
    }

    @Test
    void capsMultiplierAndDiscountNeverExceedsTotal() {
        FareBreakdown f = FareCalculator.calculate(config(0, 10_000, 15_000, List.of()), 10_000, 0, 40_000, 999_999);
        assertThat(f.multiplierBp()).isEqualTo(15_000);
        assertThat(f.totalCents()).isEqualTo(3_750);
        assertThat(f.discountCents()).isEqualTo(3_750);
        assertThat(f.finalCents()).isZero();
        // comissão sobre o total, antes do desconto (desconto subsidiado pela plataforma)
        assertThat(f.commissionCents()).isEqualTo(750);
    }

    @Test
    void rejectsNegativeInputs() {
        assertThatThrownBy(() -> FareCalculator.calculate(config(0, 10_000, 15_000, List.of()), -1, 0, 10_000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overnightTimeWindowAppliesAcrossMidnight() {
        var window = new FareConfig.TimeWindow(List.of(5), "22:00", "05:00", 12_000, "Madrugada"); // sexta
        FareConfig cfg = config(0, 10_000, 20_000, List.of(window));
        ZoneId sp = ZoneId.of("America/Sao_Paulo");
        // sexta 23:00 → dentro
        assertThat(FareCalculator.effectiveMultiplier(cfg, Money.BP_ONE, ZonedDateTime.of(2026, 10, 23, 23, 0, 0, 0, sp)))
                .isEqualTo(12_000);
        // sábado 03:00 (janela começou sexta) → dentro
        assertThat(FareCalculator.effectiveMultiplier(cfg, Money.BP_ONE, ZonedDateTime.of(2026, 10, 24, 3, 0, 0, 0, sp)))
                .isEqualTo(12_000);
        // sábado 23:00 → fora
        assertThat(FareCalculator.effectiveMultiplier(cfg, Money.BP_ONE, ZonedDateTime.of(2026, 10, 24, 23, 0, 0, 0, sp)))
                .isEqualTo(10_000);
    }

    @Test
    void combinesRegionAndDemandMultipliersUpToCap() {
        FareConfig cfg = config(0, 12_000, 14_000, List.of());
        long m = FareCalculator.effectiveMultiplier(cfg, 12_000, ZonedDateTime.now(ZoneId.of("America/Sao_Paulo")));
        assertThat(m).isEqualTo(14_000); // 1,2 × 1,2 = 1,44 → limitado a 1,4
    }
}
