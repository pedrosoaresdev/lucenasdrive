package com.lucenadrive.admin.security;

import java.time.Instant;

/** adminSessions/{id}: sessão administrativa pós-MFA, revogável individualmente. */
public record AdminSession(String id, String uid, Instant createdAt, Instant expiresAt, Instant mfaVerifiedAt,
                           String ip, String userAgent, boolean revoked, Instant revokedAt, String revokedBy) {

    public AdminSession revoke(String by, Instant at) {
        return new AdminSession(id, uid, createdAt, expiresAt, mfaVerifiedAt, ip, userAgent, true, at, by);
    }

    public AdminSession stepUp(Instant at) {
        return new AdminSession(id, uid, createdAt, expiresAt, at, ip, userAgent, revoked, revokedAt, revokedBy);
    }
}
