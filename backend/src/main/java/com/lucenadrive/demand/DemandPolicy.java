package com.lucenadrive.demand;

import java.time.Instant;
import java.util.List;

/**
 * demandPolicies/{cityId}: preço dinâmico por demanda, OPCIONAL e desligado por padrão.
 * ratio = passageiros pedindo preço nos últimos minutos ÷ motoristas disponíveis. O primeiro degrau
 * (do maior para o menor) cuja razão mínima for atingida define o multiplicador, sempre limitado por maxBp
 * e pelo teto da tarifa da categoria. Alterações são auditadas.
 */
public record DemandPolicy(String cityId, boolean enabled, List<Step> steps, long maxBp, int windowMinutes,
                           String updatedBy, Instant updatedAt) {

    public record Step(double minRatio, long multiplierBp) {
    }

    public static DemandPolicy defaults(String cityId) {
        return new DemandPolicy(cityId, false, List.of(new Step(2.0, 12_000), new Step(3.0, 14_000), new Step(5.0, 16_000)),
                16_000, 10, null, null);
    }

    /** Multiplicador para a razão observada (1,0× = 10 000 pontos-base). */
    public long multiplierFor(double ratio) {
        long bp = 10_000;
        for (Step s : steps == null ? List.<Step>of() : steps) {
            if (ratio >= s.minRatio()) {
                bp = Math.max(bp, s.multiplierBp());
            }
        }
        return Math.min(bp, Math.max(10_000, maxBp));
    }
}
