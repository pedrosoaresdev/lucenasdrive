package com.lucenadrive.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.demand.DemandPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemandPolicyTest {

    @Test
    void picksHighestReachedStepAndRespectsCap() {
        DemandPolicy p = new DemandPolicy("c", true, List.of(new DemandPolicy.Step(2, 12_000), new DemandPolicy.Step(3, 14_000),
                new DemandPolicy.Step(5, 18_000)), 15_000, 10, null, null);
        assertThat(p.multiplierFor(0)).isEqualTo(10_000);
        assertThat(p.multiplierFor(1.9)).isEqualTo(10_000);
        assertThat(p.multiplierFor(2)).isEqualTo(12_000);
        assertThat(p.multiplierFor(3.5)).isEqualTo(14_000);
        assertThat(p.multiplierFor(9)).isEqualTo(15_000); // teto
        assertThat(DemandPolicy.defaults("c").enabled()).isFalse();
    }
}
