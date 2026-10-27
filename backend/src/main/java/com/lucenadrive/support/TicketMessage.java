package com.lucenadrive.support;

import java.time.Instant;

public record TicketMessage(String id, String ticketId, String authorId, boolean fromStaff, String text,
                            String attachmentPath, Instant createdAt) {
}
