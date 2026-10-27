package com.lucenadrive.ride;

import com.lucenadrive.common.web.Ids;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.persistence.Tx;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RideEventService {

    private final DocStore store;
    private final Clock clock;

    public RideEventService(DocStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public void recordInTx(Tx tx, String rideId, String type, RideStatus from, RideStatus to, String actorId,
                           ActorRole role, Map<String, Object> data) {
        RideEvent e = new RideEvent(Ids.newId(), rideId, type, from, to, actorId, role, data, Instant.now(clock));
        tx.set(Collections.RIDE_EVENTS, e.id(), e);
    }

    public List<RideEvent> list(String rideId) {
        return store.query(Query.of(Collections.RIDE_EVENTS).eq("rideId", rideId)
                .orderBy("at", Query.Direction.ASC).limit(200), RideEvent.class);
    }
}
