package com.lucenadrive.fare;

import com.lucenadrive.common.money.Money;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Cálculo tarifário puro e determinístico (sem I/O).
 * <ul>
 *   <li>subtotal = base + valor/km × km + valor/min × min</li>
 *   <li>total = max(mínima, subtotal × multiplicador), multiplicador limitado ao teto</li>
 *   <li>comissão = total × comissão% (sobre o valor antes do desconto)</li>
 *   <li>ganho do motorista = total − comissão; o desconto do cupom é subsidiado pela plataforma</li>
 *   <li>valor pago pelo passageiro = total − desconto</li>
 * </ul>
 */
public final class FareCalculator {

    private FareCalculator() {
    }

    public static FareBreakdown calculate(FareConfig cfg, long distanceMeters, long durationSeconds,
                                          long multiplierBp, long discountCents) {
        if (distanceMeters < 0 || durationSeconds < 0 || discountCents < 0) {
            throw new IllegalArgumentException("Valores negativos não são permitidos");
        }
        long cappedMultiplier = Math.max(Money.BP_ONE, Math.min(multiplierBp, cfg.maxMultiplierBp()));
        long distanceCents = Money.proportional(cfg.perKmCents(), distanceMeters, 1000);
        long timeCents = Money.proportional(cfg.perMinuteCents(), durationSeconds, 60);
        long subtotal = cfg.baseFareCents() + distanceCents + timeCents;
        long multiplied = Money.applyBasisPoints(subtotal, cappedMultiplier);
        boolean minimumApplied = multiplied < cfg.minimumFareCents();
        long total = Math.max(multiplied, cfg.minimumFareCents());
        long discount = Math.min(discountCents, total);
        long commission = Money.applyBasisPoints(total, cfg.commissionBp());
        return new FareBreakdown(cfg.id(), cfg.version(), cfg.baseFareCents(), distanceCents, timeCents, subtotal,
                cappedMultiplier, minimumApplied, total, discount, total - discount, cfg.commissionBp(), commission,
                total - commission, cfg.cancellationFeeCents());
    }

    /** Multiplicador efetivo = demanda × região × janela horária vigente (limitado pelo teto). */
    public static long effectiveMultiplier(FareConfig cfg, long regionBp, ZonedDateTime localNow) {
        return effectiveMultiplier(cfg, regionBp, localNow, Money.BP_ONE);
    }

    /**
     * Com preço dinâmico: o fator de demanda é o maior entre o manual da tarifa e o dinâmico da cidade
     * (não se acumulam); o resultado continua limitado pelo teto da tarifa.
     */
    public static long effectiveMultiplier(FareConfig cfg, long regionBp, ZonedDateTime localNow, long dynamicBp) {
        long windowBp = Money.BP_ONE;
        if (cfg.timeWindows() != null) {
            for (FareConfig.TimeWindow w : cfg.timeWindows()) {
                if (inWindow(w, localNow)) {
                    windowBp = Math.max(windowBp, w.multiplierBp());
                }
            }
        }
        long combined = Money.applyBasisPoints(Money.applyBasisPoints(
                Math.max(Money.BP_ONE, Math.max(cfg.demandMultiplierBp(), dynamicBp)), regionBp), windowBp);
        return Math.min(combined, cfg.maxMultiplierBp());
    }

    static boolean inWindow(FareConfig.TimeWindow w, ZonedDateTime now) {
        DayOfWeek day = now.getDayOfWeek();
        LocalTime t = now.toLocalTime();
        LocalTime start = LocalTime.parse(w.start());
        LocalTime end = LocalTime.parse(w.end());
        boolean overnight = end.isBefore(start);
        if (!overnight) {
            return w.daysOfWeek().contains(day.getValue()) && !t.isBefore(start) && t.isBefore(end);
        }
        // Janela que atravessa a meia-noite: pertence ao dia em que começou.
        if (!t.isBefore(start)) {
            return w.daysOfWeek().contains(day.getValue());
        }
        return t.isBefore(end) && w.daysOfWeek().contains(day.minus(1).getValue());
    }
}
