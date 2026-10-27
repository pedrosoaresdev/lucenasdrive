package com.lucenadrive.rating;

import java.time.Instant;
import java.util.List;

/**
 * ratings/{rideId}_{raterRole}: avaliação de 1 a 5 estrelas. O formato (raterRole/rateeRole)
 * já comporta a avaliação recíproca futura (motorista → passageiro).
 * VISIBLE: conta na média; COMMENT_HIDDEN: nota conta, comentário moderado; INVALIDATED: removida
 * da média por decisão administrativa justificada e auditada.
 */
public record Rating(String id, String rideId, String raterId, RaterRole raterRole, String rateeId, RaterRole rateeRole,
                     int stars, String comment, List<String> tags, Status status, boolean reported,
                     List<ModerationAction> moderation, Instant createdAt, Instant updatedAt) {

    public enum RaterRole { PASSENGER, DRIVER }

    public enum Status { VISIBLE, COMMENT_HIDDEN, INVALIDATED }

    public record ModerationAction(String action, String byUserId, String reason, Instant at) {
    }

    public static String idFor(String rideId, RaterRole raterRole) {
        return rideId + "_" + raterRole.name();
    }

    public boolean countsInAverage() {
        return status != Status.INVALIDATED;
    }

    public Rating moderate(Status next, String action, String by, String reason, Instant at, boolean reportedFlag) {
        List<ModerationAction> history = new java.util.ArrayList<>(moderation == null ? List.of() : moderation);
        history.add(new ModerationAction(action, by, reason, at));
        return new Rating(id, rideId, raterId, raterRole, rateeId, rateeRole, stars, comment, tags, next, reportedFlag,
                history, createdAt, at);
    }
}
