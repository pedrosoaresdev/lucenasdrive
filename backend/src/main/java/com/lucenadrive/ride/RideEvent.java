package com.lucenadrive.ride;

import java.time.Instant;
import java.util.Map;

/** rideEvents/{id}: trilha imutável dos eventos operacionais da corrida. */
public record RideEvent(String id, String rideId, String type, RideStatus fromStatus, RideStatus toStatus,
                        String actorId, ActorRole actorRole, Map<String, Object> data, Instant at) {
}
