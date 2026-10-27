package com.lucenadrive.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Registro imutável de operação privilegiada (adminAuditLogs). */
public record AuditLog(
        String id,
        String actorId,
        List<String> actorRoles,
        String action,
        String targetType,
        String targetId,
        Map<String, Object> before,
        Map<String, Object> after,
        String reason,
        String ip,
        String userAgent,
        Instant at) {
}
