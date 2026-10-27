package com.lucenadrive.payment.webhook;

import java.time.Instant;

/** webhookEvents/{provider}_{eventId}: registro de recebimento (idempotência e auditoria). */
public record WebhookEventRecord(String id, String provider, String eventId, ProviderEvent.Type type,
                                 String status, String error, Instant receivedAt, Instant processedAt) {
}
