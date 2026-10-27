package com.lucenadrive.payment.webhook;

import java.time.Instant;

/** Evento do provedor já traduzido para o formato interno (cada adaptador faz a tradução). */
public record ProviderEvent(String provider, String eventId, Type type, String chargeId, String transferId,
                            Long amountCents, Long feeCents, String receiptUrl, String failureReason,
                            Instant occurredAt) {

    public enum Type {
        PAYMENT_CONFIRMED, PAYMENT_SETTLED, PAYMENT_FAILED, PAYMENT_REFUNDED, CHARGEBACK,
        TRANSFER_COMPLETED, TRANSFER_FAILED
    }
}
