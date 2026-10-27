package com.lucenadrive.security;

import com.lucenadrive.user.UserStatus;
import java.time.Instant;
import java.util.Set;

/**
 * Principal autenticado. Papéis e status vêm do documento users/{uid} (gravado somente pelo
 * backend), nunca do que o cliente envia.
 */
public record AuthUser(
        String uid,
        String email,
        boolean emailVerified,
        String phoneNumber,
        Set<Role> roles,
        UserStatus status,
        Instant authTime,
        Instant mfaVerifiedAt,
        String adminSessionId,
        boolean registered) {

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    public boolean isStaff() {
        return roles.stream().anyMatch(Role::isStaff);
    }

    public boolean mfaVerified() {
        return mfaVerifiedAt != null;
    }

    public AuthUser withMfa(Instant verifiedAt, String sessionId) {
        return new AuthUser(uid, email, emailVerified, phoneNumber, roles, status, authTime, verifiedAt, sessionId, registered);
    }
}
