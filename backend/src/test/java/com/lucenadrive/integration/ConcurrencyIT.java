package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.security.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/** Operações críticas sob concorrência: aceite duplo de corrida e limite global de cupom. */
class ConcurrencyIT extends IntegrationTestBase {

    private int post(String path, Actor actor, Object body, Map<String, String> headers) throws Exception {
        var req = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + actor.token());
        headers.forEach(req::header);
        if (body != null) {
            req.content(json.writeValueAsBytes(body));
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private List<Integer> race(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> t : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return t.call();
            }));
        }
        start.countDown();
        List<Integer> out = new ArrayList<>();
        for (Future<Integer> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    @Test
    void onlyOneDriverCanAcceptTheSameRide() throws Exception {
        Actor admin = staffWithMfa(Role.ADMIN);
        String cityId = seedCityWithFares(admin, "Cidade Concorrência");
        List<Actor> drivers = List.of(approvedOnlineDriver(admin, cityId, LAT + 0.01, LNG),
                approvedOnlineDriver(admin, cityId, LAT, LNG + 0.01), approvedOnlineDriver(admin, cityId, LAT - 0.01, LNG));
        Actor pax = passenger();
        JsonNode est = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.03, LNG, "B")), 200);
        String rideId = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId",
                est.get("quotes").get(0).get("quoteId").asString(), "paymentMethod", "CASH"), 201, idem()).get("id").asString();
        for (Actor d : drivers) {
            assertThat(call(HttpMethod.GET, "/api/v1/driver/offers", d, null, 200)).hasSize(1);
        }
        List<Integer> statuses = race(drivers.stream().<Callable<Integer>>map(d ->
                () -> post("/api/v1/driver/offers/" + rideId + "/accept", d, null, Map.of())).toList());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409, 409);
        JsonNode ride = call(HttpMethod.GET, "/api/v1/rides/" + rideId, pax, null, 200);
        assertThat(ride.get("status").asString()).isEqualTo("ACCEPTED");
        long busy = drivers.stream().filter(d -> !call(HttpMethod.GET, "/api/v1/drivers/me", d, null, 200)
                .get("currentRideId").isNull()).count();
        assertThat(busy).isEqualTo(1);
    }

    @Test
    void couponGlobalLimitHoldsUnderConcurrentRequests() throws Exception {
        Actor admin = staffWithMfa(Role.ADMIN);
        String cityId = seedCityWithFares(admin, "Cidade Cupom");
        approvedOnlineDriver(admin, cityId, LAT, LNG);
        call(HttpMethod.POST, "/api/v1/admin/coupons", admin, Map.of("code", "UNICO1", "type", "FIXED",
                "amountCents", 300, "maxRedemptions", 1, "active", true), 201);
        List<Actor> pax = List.of(passenger(), passenger(), passenger(), passenger());
        List<String> quotes = new ArrayList<>();
        for (Actor p : pax) {
            JsonNode est = call(HttpMethod.POST, "/api/v1/rides/estimate", p, Map.of("origin", place(LAT, LNG, "A"),
                    "destination", place(LAT + 0.03, LNG, "B"), "couponCode", "UNICO1"), 200);
            assertThat(est.get("quotes").get(0).get("discountCents").asLong()).isEqualTo(300);
            quotes.add(est.get("quotes").get(0).get("quoteId").asString());
        }
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < pax.size(); i++) {
            Actor p = pax.get(i);
            String q = quotes.get(i);
            tasks.add(() -> post("/api/v1/rides", p, Map.of("quoteId", q, "paymentMethod", "CASH"), idem()));
        }
        List<Integer> statuses = race(tasks);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(3);
        JsonNode coupon = call(HttpMethod.GET, "/api/v1/admin/coupons/UNICO1/results", admin, null, 200);
        assertThat(coupon.get("coupon").get("redemptionCount").asLong()).isEqualTo(1);
    }
}
