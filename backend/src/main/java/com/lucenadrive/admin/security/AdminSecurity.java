package com.lucenadrive.admin.security;

import java.time.Instant;
import java.util.List;

/**
 * adminSecurity/{uid}: segundo fator da equipe. Segredo TOTP criptografado; códigos de recuperação
 * guardados apenas como hash (exibidos uma única vez). Sem acesso pelo cliente.
 */
public record AdminSecurity(String id, String totpSecretEncrypted, String pendingSecretEncrypted, boolean totpEnabled,
                            Instant enrolledAt, List<String> recoveryCodeHashes, int failedAttempts,
                            Instant lockedUntil, long lastUsedTimeStep, List<String> knownDeviceHashes,
                            Instant updatedAt) {

    public static AdminSecurity empty(String uid, Instant now) {
        return new AdminSecurity(uid, null, null, false, null, List.of(), 0, null, -1, List.of(), now);
    }
}
