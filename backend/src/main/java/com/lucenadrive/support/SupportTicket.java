package com.lucenadrive.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** supportTickets/{id}: chamado de suporte/ocorrência. Mensagens em supportTickets/{id}/messages. */
public record SupportTicket(String id, String userId, String userRole, Category category, Priority priority,
                            Status status, String rideId, String subject, String description,
                            List<String> attachmentPaths, String assigneeId, Map<String, String> metadata,
                            List<StatusChange> history, Instant createdAt, Instant updatedAt) {

    public enum Category { RIDE_ISSUE, PAYMENT, SAFETY, ACCOUNT, RATING_DISPUTE, LOST_ITEM, INAPPROPRIATE_BEHAVIOR, OTHER }

    public enum Priority { LOW, NORMAL, HIGH, URGENT }

    public enum Status { OPEN, IN_PROGRESS, WAITING_USER, ESCALATED, RESOLVED, CLOSED }

    public record StatusChange(Status status, String byUserId, String note, Instant at) {
    }

    public SupportTicket with(Status s, Priority p, String assignee, String by, String note, Instant at) {
        List<StatusChange> h = new java.util.ArrayList<>(history == null ? List.of() : history);
        h.add(new StatusChange(s, by, note, at));
        return new SupportTicket(id, userId, userRole, category, p, s, rideId, subject, description, attachmentPaths,
                assignee, metadata, h, createdAt, at);
    }
}
