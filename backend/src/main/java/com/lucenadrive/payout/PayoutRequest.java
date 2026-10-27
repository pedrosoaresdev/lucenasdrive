package com.lucenadrive.payout;

import java.time.Instant;
import java.util.List;

/** payoutRequests/{id}: solicitação de saque/repasse via Pix (motorista ou plataforma). */
public record PayoutRequest(String id, String ownerId, OwnerType ownerType, long amountCents, String pixKeyMasked,
                            PixKey.KeyType pixKeyType, Status status, String providerTransferId, String receiptUrl,
                            String failureReason, String requestedBy, String idempotencyKey,
                            List<StatusChange> history, Instant createdAt, Instant updatedAt) {

    public enum OwnerType { DRIVER, PLATFORM }

    public enum Status { REQUESTED, IN_VALIDATION, PROCESSING, COMPLETED, REJECTED, FAILED, CANCELLED }

    public record StatusChange(Status status, String by, String note, Instant at) {
    }

    public boolean finished() {
        return status == Status.COMPLETED || status == Status.REJECTED || status == Status.FAILED
                || status == Status.CANCELLED;
    }

    public PayoutRequest with(Status s, String by, String note, Instant at, String transferId, String receipt,
                              String failure) {
        List<StatusChange> h = new java.util.ArrayList<>(history == null ? List.of() : history);
        h.add(new StatusChange(s, by, note, at));
        return new PayoutRequest(id, ownerId, ownerType, amountCents, pixKeyMasked, pixKeyType, s,
                transferId != null ? transferId : providerTransferId, receipt != null ? receipt : receiptUrl,
                failure != null ? failure : failureReason, requestedBy, idempotencyKey, h, createdAt, at);
    }
}
