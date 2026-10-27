package com.lucenadrive.ledger;

import java.time.Instant;
import java.util.List;

/**
 * ledgerEntries/{id}: lançamento financeiro. Valor, conta, tipo e direção são imutáveis; apenas o
 * status de liquidação evolui, com histórico (statusHistory). Correções geram novos lançamentos
 * (REVERSAL/ADJUSTMENT) que referenciam o original — nada é apagado.
 * O id é derivado da chave de idempotência, então o mesmo fato nunca é lançado duas vezes.
 */
public record LedgerEntry(
        String id,
        LedgerEntryType type,
        String account,
        Direction direction,
        long amountCents,
        String currency,
        String rideId,
        String userId,
        String provider,
        String externalRef,
        LedgerStatus status,
        List<StatusChange> statusHistory,
        String idempotencyKey,
        String reversalOf,
        String description,
        Instant createdAt,
        Instant updatedAt) {

    public enum Direction { CREDIT, DEBIT }

    public record StatusChange(LedgerStatus status, Instant at, String reason) {
    }

    public long signedAmount() {
        return direction == Direction.CREDIT ? amountCents : -amountCents;
    }

    public static final String PLATFORM = "PLATFORM";

    public static String driverAccount(String driverId) {
        return "DRIVER:" + driverId;
    }

    public LedgerEntry withStatus(LedgerStatus next, Instant at, String reason, String newExternalRef) {
        List<StatusChange> history = new java.util.ArrayList<>(statusHistory == null ? List.of() : statusHistory);
        history.add(new StatusChange(next, at, reason));
        return new LedgerEntry(id, type, account, direction, amountCents, currency, rideId, userId, provider,
                newExternalRef != null ? newExternalRef : externalRef, next, history, idempotencyKey, reversalOf,
                description, createdAt, at);
    }
}
