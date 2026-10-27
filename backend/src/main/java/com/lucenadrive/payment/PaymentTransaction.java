package com.lucenadrive.payment;

import java.time.Instant;
import java.util.List;

/** paymentTransactions/{id}: cobrança relacionada a uma corrida. */
public record PaymentTransaction(String id, String rideId, String passengerId, String driverId, PaymentMethod method,
                                 long amountCents, String currency, PaymentStatus status, String provider,
                                 String providerChargeId, Long providerFeeCents, List<StatusChange> statusHistory,
                                 String idempotencyKey, Instant createdAt, Instant updatedAt) {

    public record StatusChange(PaymentStatus status, Instant at, String source) {
    }

    public PaymentTransaction withStatus(PaymentStatus next, Instant at, String source, Long fee) {
        List<StatusChange> h = new java.util.ArrayList<>(statusHistory == null ? List.of() : statusHistory);
        h.add(new StatusChange(next, at, source));
        return new PaymentTransaction(id, rideId, passengerId, driverId, method, amountCents, currency, next, provider,
                providerChargeId, fee != null ? fee : providerFeeCents, h, idempotencyKey, createdAt, at);
    }
}
