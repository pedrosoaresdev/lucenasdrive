package com.lucenadrive.user;

import java.time.Instant;

/** Solicitações de titulares (LGPD art. 18). Tratadas pela equipe com prazo e registro. */
public record PrivacyRequest(String id, String userId, Type type, Status status, String details,
                             String resolutionNote, String handledBy, Instant createdAt, Instant updatedAt) {

    public enum Type { DATA_EXPORT, ACCOUNT_DELETION, CORRECTION, CONSENT_REVOCATION }

    public enum Status { OPEN, IN_PROGRESS, COMPLETED, REJECTED }
}
