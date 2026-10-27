package com.lucenadrive.ride;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.driver.DriverLocation;
import com.lucenadrive.geo.GeoPoint;
import com.lucenadrive.geo.RouteResult;
import com.lucenadrive.geo.RouteService;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Atualização de localização do motorista. O cliente adapta a frequência (≈4 s em corrida,
 * ≈15 s online sem corrida); o servidor limita a 1 atualização a cada 2 s por motorista.
 * Durante a corrida, apenas os campos de posição/ETA do documento da corrida são atualizados
 * (escrita parcial, sem sobrescrever o status), e o passageiro recebe via listener.
 */
@Service
public class LocationService {

    private static final Duration ETA_REFRESH = Duration.ofSeconds(60);

    public record LocationUpdate(double lat, double lng, Double heading, Double speedMps, Double accuracyM) {
    }

    private final DocStore store;
    private final RateLimiter limiter;
    private final RouteService routes;
    private final Clock clock;
    private final Map<String, Instant> lastEta = new ConcurrentHashMap<>();

    public LocationService(DocStore store, RateLimiter limiter, RouteService routes, Clock clock) {
        this.store = store;
        this.limiter = limiter;
        this.routes = routes;
        this.clock = clock;
    }

    public void update(AuthUser driver, LocationUpdate u) {
        if (!limiter.tryAcquire("loc:" + driver.uid(), 1, 2_000)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Atualizações de localização muito frequentes.");
        }
        DriverLocation loc = store.get(Collections.DRIVER_LOCATIONS, driver.uid(), DriverLocation.class)
                .filter(DriverLocation::online)
                .orElseThrow(() -> new ApiException(ErrorCode.DRIVER_NOT_ELIGIBLE, "Fique online para compartilhar localização."));
        Instant now = Instant.now(clock);
        Map<String, Object> fields = new HashMap<>();
        fields.put("lat", u.lat());
        fields.put("lng", u.lng());
        fields.put("heading", u.heading());
        fields.put("speedMps", u.speedMps());
        fields.put("accuracyM", u.accuracyM());
        fields.put("updatedAt", now);
        fields.values().removeIf(java.util.Objects::isNull);
        store.update(Collections.DRIVER_LOCATIONS, driver.uid(), fields);

        if (loc.currentRideId() == null) {
            return;
        }
        Ride ride = store.get(Collections.RIDES, loc.currentRideId(), Ride.class).orElse(null);
        if (ride == null || !driver.uid().equals(ride.driverId()) || !RideStatus.WITH_DRIVER.contains(ride.status())) {
            return;
        }
        Map<String, Object> rideFields = new HashMap<>();
        Map<String, Object> pos = new HashMap<>();
        pos.put("lat", u.lat());
        pos.put("lng", u.lng());
        if (u.heading() != null) {
            pos.put("heading", u.heading());
        }
        pos.put("updatedAt", now);
        rideFields.put("driverPosition", pos);
        Long eta = refreshEta(ride, new GeoPoint(u.lat(), u.lng()), now);
        if (eta != null) {
            rideFields.put("etaSeconds", eta);
        }
        store.update(Collections.RIDES, ride.id(), rideFields);
    }

    /** ETA via Routes API no máximo a cada 60 s por corrida (controle de custo). */
    private Long refreshEta(Ride ride, GeoPoint here, Instant now) {
        if (!routes.available() || ride.status() == RideStatus.DRIVER_ARRIVED) {
            return null;
        }
        Instant last = lastEta.get(ride.id());
        if (last != null && last.plus(ETA_REFRESH).isAfter(now)) {
            return null;
        }
        lastEta.put(ride.id(), now);
        GeoPoint target = ride.status() == RideStatus.IN_PROGRESS ? ride.destination().point() : ride.origin().point();
        try {
            RouteResult r = routes.route(here, target);
            return r.durationSeconds();
        } catch (ApiException e) {
            return null;
        }
    }
}
