package com.lucenadrive.fare;

/** Composição de preço preservada na corrida (não muda se a tarifa for alterada depois). */
public record FareBreakdown(
        String fareConfigId,
        int fareConfigVersion,
        long baseFareCents,
        long distanceCents,
        long timeCents,
        long subtotalCents,
        long multiplierBp,
        boolean minimumApplied,
        long totalCents,
        long discountCents,
        long finalCents,
        long commissionBp,
        long commissionCents,
        long driverEarningCents,
        long cancellationFeeCents) {
}
