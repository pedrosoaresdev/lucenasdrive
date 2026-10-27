package com.lucenadrive.fare;

import java.time.Instant;
import java.util.List;

/**
 * fareConfigs/{cityId}_{category}: tarifa vigente. Valores em centavos; percentuais e
 * multiplicadores em pontos-base. Cada alteração gera um documento em fareConfigVersions.
 */
public record FareConfig(
        String id,
        String cityId,
        VehicleCategory category,
        long baseFareCents,
        long perKmCents,
        long perMinuteCents,
        long minimumFareCents,
        long cancellationFeeCents,
        long commissionBp,
        long demandMultiplierBp,
        long maxMultiplierBp,
        List<TimeWindow> timeWindows,
        boolean active,
        int version,
        Instant effectiveFrom,
        String updatedBy,
        Instant updatedAt) {

    public static String idFor(String cityId, VehicleCategory category) {
        return cityId + "_" + category.name();
    }

    /** Janela horária com multiplicador (ex.: madrugada). Dias 1=segunda … 7=domingo. */
    public record TimeWindow(List<Integer> daysOfWeek, String start, String end, long multiplierBp, String label) {
    }
}
