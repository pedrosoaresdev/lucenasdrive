package com.lucenadrive.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Operações monetárias em centavos (long). Percentuais e multiplicadores são expressos em
 * pontos-base (1 bp = 0,01%; 10_000 bp = 100% = multiplicador 1,0). Nunca usa double.
 */
public final class Money {

    public static final String CURRENCY = "BRL";
    public static final long BP_ONE = 10_000L;

    private Money() {
    }

    /** Aplica pontos-base sobre um valor em centavos com arredondamento HALF_UP. */
    public static long applyBasisPoints(long cents, long basisPoints) {
        return BigDecimal.valueOf(cents)
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(BP_ONE), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Valor proporcional: rate (centavos por unidade) × quantidade / divisor, HALF_UP. */
    public static long proportional(long rateCents, long quantity, long divisor) {
        return BigDecimal.valueOf(rateCents)
                .multiply(BigDecimal.valueOf(quantity))
                .divide(BigDecimal.valueOf(divisor), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    public static String format(long cents) {
        BigDecimal value = BigDecimal.valueOf(cents).movePointLeft(2);
        return "R$ " + value.toPlainString().replace('.', ',');
    }
}
