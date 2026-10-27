package com.lucenadrive.admin.security;

import java.time.Instant;
import java.util.Map;

/** securityEvents/{id}: eventos de segurança (MFA, sessões, novos dispositivos). */
public record SecurityEvent(String id, String uid, String type, String ip, String userAgent,
                            Map<String, Object> data, Instant at) {
}
