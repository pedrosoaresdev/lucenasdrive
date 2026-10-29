package com.lucenadrive.ride;

import java.time.Instant;
import java.util.List;

/**
 * rideDispatch/{rideId}: estado interno do despacho (não legível por clientes).
 * redispatches: quantas vezes a corrida voltou à busca porque o motorista cancelou antes da viagem;
 * excludedDriverIds: motoristas que desistiram desta corrida (não recebem nova oferta dela).
 */
public record RideDispatch(String rideId, String cityId, int batches, List<String> offeredDriverIds,
                           Instant searchStartedAt, Instant lastBatchAt, boolean finished, int redispatches,
                           List<String> excludedDriverIds) {

    public RideDispatch finish() {
        return new RideDispatch(rideId, cityId, batches, offeredDriverIds, searchStartedAt, lastBatchAt, true, redispatches,
                excludedDriverIds);
    }

    public boolean excludes(String driverId) {
        return offeredDriverIds.contains(driverId) || (excludedDriverIds != null && excludedDriverIds.contains(driverId));
    }
}
