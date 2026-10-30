package com.lucenadrive.payment;

import java.time.Instant;
import java.util.List;

/**
 * paymentTransactions/{id}: cobrança relacionada a uma corrida. splitCents preenchido = a parte do
 * motorista é repassada pelo próprio provedor (split) e não entra no saldo de saque da plataforma.
 */
public record PaymentTransaction(String id, String rideId, String passengerId, String driverId, PaymentMethod method,
                                 long amountCents, String currency, PaymentStatus status, String provider,
                                 String providerChargeId, Long providerFeeCents, List<StatusChange> statusHistory,
                                 String idempotencyKey, Instant createdAt, Instant updatedAt,
                                 String pixCopyPaste, String pixQrCodeBase64, String checkoutUrl,
                                 String splitWalletId, Long splitCents) {

    public record StatusChange(PaymentStatus status, Instant at, String source) {
    }

    public PaymentTransaction withStatus(PaymentStatus next, Instant at, String source, Long fee) {
        List<StatusChange> h = new java.util.ArrayList<>(statusHistory == null ? List.of() : statusHistory);
        h.add(new StatusChange(next, at, source));
        return new PaymentTransaction(id, rideId, passengerId, driverId, method, amountCents, currency, next, provider,
                providerChargeId, fee != null ? fee : providerFeeCents, h, idempotencyKey, createdAt, at,
                pixCopyPaste, pixQrCodeBase64, checkoutUrl, splitWalletId, splitCents);
    }

    public PaymentTransaction withCharge(String providerName, PaymentProvider.ChargeResult r, String walletId,
                                         Long split, Instant at) {
        return new PaymentTransaction(id, rideId, passengerId, driverId, method, amountCents, currency, status,
                providerName, r.providerChargeId(), providerFeeCents, statusHistory, idempotencyKey, createdAt, at,
                r.pixCopyPaste(), r.pixQrCodeBase64(), r.checkoutUrl(), r.splitApplied() ? walletId : null,
                r.splitApplied() ? split : null);
    }

    public boolean splitToDriver() {
        return splitCents != null && splitCents > 0;
    }
}
