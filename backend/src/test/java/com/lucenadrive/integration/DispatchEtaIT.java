package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.ride.RideOffer;
import com.lucenadrive.security.Role;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Despacho por MENOR tempo de chegada: o servidor OSRM simulado informa que o motorista mais distante em linha reta
 * chega antes (ex.: o mais próximo está do outro lado de um rio). A oferta deve ir primeiro para o de menor ETA.
 */
class DispatchEtaIT extends IntegrationTestBase {

    static final AtomicReference<String> tableReply = new AtomicReference<>();
    static final HttpServer OSRM = start();

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                String body = path.startsWith("/table/") ? tableReply.get()
                        : "{\"code\":\"Ok\",\"routes\":[{\"distance\":3500,\"duration\":480,\"geometry\":\"\"}]}";
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void osrm(DynamicPropertyRegistry r) {
        r.add("lucena.maps.osrm-url", () -> "http://127.0.0.1:" + OSRM.getAddress().getPort());
        r.add("lucena.dispatch.batch-size", () -> "1");
    }

    @AfterAll
    static void stop() {
        OSRM.stop(0);
    }

    @Test
    void offerGoesToTheFreeDriverWithTheLowestEta() {
        Actor admin = staffWithMfa(Role.ADMIN);
        String cityId = seedCityWithFares(admin, "Cidade ETA");
        Actor near = approvedOnlineDriver(admin, cityId, LAT + 0.002, LNG);   // ~220 m em linha reta
        Actor far = approvedOnlineDriver(admin, cityId, LAT + 0.02, LNG);     // ~2,2 km em linha reta
        // Origens da matriz vêm ordenadas por distância: [near, far] → near 15 min, far 4 min.
        tableReply.set("{\"code\":\"Ok\",\"durations\":[[900.0],[240.0]]}");
        Actor pax = passenger();
        JsonNode est = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.03, LNG, "B")), 200);
        String rideId = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId",
                est.get("quotes").get(0).get("quoteId").asString(), "paymentMethod", "CASH"), 201, idem()).get("id").asString();

        List<RideOffer> offers = store.query(Query.of(Collections.RIDE_OFFERS).eq("rideId", rideId), RideOffer.class);
        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).driverId()).isEqualTo(far.uid());
        assertThat(offers.get(0).etaToPickupSeconds()).isEqualTo(240L);
        JsonNode driverOffers = call(HttpMethod.GET, "/api/v1/driver/offers", far, null, 200);
        assertThat(driverOffers.toString()).contains("\"etaToPickupSeconds\":240");
        call(HttpMethod.POST, "/api/v1/driver/offers/" + rideId + "/accept", near, null, 403);
    }

    @Test
    void routingFailureFallsBackToDistanceOrder() {
        Actor admin = staffWithMfa(Role.ADMIN);
        String cityId = seedCityWithFares(admin, "Cidade ETA Falha");
        Actor near = approvedOnlineDriver(admin, cityId, LAT + 0.002, LNG);
        approvedOnlineDriver(admin, cityId, LAT + 0.02, LNG);
        tableReply.set("{\"code\":\"InvalidQuery\"}");
        Actor pax = passenger();
        JsonNode est = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.03, LNG, "B")), 200);
        String rideId = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId",
                est.get("quotes").get(0).get("quoteId").asString(), "paymentMethod", "CASH"), 201, idem()).get("id").asString();
        List<RideOffer> offers = store.query(Query.of(Collections.RIDE_OFFERS).eq("rideId", rideId), RideOffer.class);
        assertThat(offers).hasSize(1);
        // a matriz falhou no OSRM → a linha reta assume (ETA estimado), mantendo o mais próximo primeiro
        assertThat(offers.get(0).driverId()).isEqualTo(near.uid());
    }
}
