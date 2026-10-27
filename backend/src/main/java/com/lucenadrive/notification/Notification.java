package com.lucenadrive.notification;

import java.time.Instant;
import java.util.Map;

/** notifications/{id}: notificação no aplicativo, lida pelo destinatário via listener do Firestore. */
public record Notification(String id, String userId, NotificationType type, String title, String body,
                           Map<String, String> data, boolean read, Instant createdAt) {
}
