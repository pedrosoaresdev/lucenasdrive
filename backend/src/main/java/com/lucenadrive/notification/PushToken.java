package com.lucenadrive.notification;

import java.time.Instant;

public record PushToken(String id, String userId, String token, String platform, Instant updatedAt) {
}
