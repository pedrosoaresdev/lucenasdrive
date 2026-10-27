package com.lucenadrive.ride;

import java.time.Instant;
import java.util.List;

/** rideDispatch/{rideId}: estado interno do despacho (não legível por clientes). */
public record RideDispatch(String rideId, String cityId, int batches, List<String> offeredDriverIds,
                           Instant searchStartedAt, Instant lastBatchAt, boolean finished) {
}
