package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.fare.FareService;
import com.lucenadrive.security.Role;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

class FareScheduleIT extends IntegrationTestBase {

    @Autowired FareService fares;

    private Map<String, Object> body(long base, String reason, Object effectiveFrom) {
        Map<String, Object> m = new java.util.HashMap<>(Map.of(
                "config", Map.of("baseFareCents", base, "perKmCents", 200, "perMinuteCents", 30, "minimumFareCents", 800,
                        "cancellationFeeCents", 700, "commissionBp", 2000, "demandMultiplierBp", 10_000,
                        "maxMultiplierBp", 20_000, "timeWindows", List.of(), "active", true),
                "reason", reason));
        m.put("effectiveFrom", effectiveFrom);
        return m;
    }

    @Test
    void scheduledFareTakesEffectAtTheRightTimeAndKeepsHistory() {
        Actor admin = staffWithMfa(Role.ADMIN);
        Actor finance = staffWithMfa(Role.FINANCE);
        String cityId = seedCityWithFares(admin, "Cidade Vigência");
        Actor pax = passenger();
        String path = "/api/v1/admin/cities/" + cityId + "/fares/ESSENCIAL";

        call(HttpMethod.PUT, path, admin, body(900, "Reajuste muito cedo", clock.instant().plus(Duration.ofMinutes(5)).toString()), 400);
        call(HttpMethod.PUT, path, finance, body(900, "Reajuste programado de teste", clock.instant().plus(Duration.ofHours(1)).toString()), 403);
        JsonNode scheduled = call(HttpMethod.PUT, path, admin,
                body(900, "Reajuste programado de teste", clock.instant().plus(Duration.ofHours(1)).toString()), 200);
        assertThat(scheduled.get("version").asInt()).isEqualTo(1);
        assertThat(scheduled.get("baseFareCents").asLong()).isEqualTo(500);
        assertThat(scheduled.get("scheduled").get("config").get("baseFareCents").asLong()).isEqualTo(900);

        Map<String, Object> trip = Map.of("origin", place(LAT, LNG, "A"), "destination", place(LAT + 0.03, LNG, "B"));
        JsonNode before = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, trip, 200).get("quotes").get(0);

        clock.advance(Duration.ofMinutes(61));
        JsonNode after = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, trip, 200).get("quotes").get(0);
        com.lucenadrive.ride.FareQuote q = store.get(com.lucenadrive.persistence.Collections.FARE_QUOTES,
                after.get("quoteId").asString(), com.lucenadrive.ride.FareQuote.class).orElseThrow();
        assertThat(q.fare().fareConfigVersion()).isEqualTo(2);
        assertThat(after.get("totalCents").asLong() - before.get("totalCents").asLong()).isEqualTo(400);

        fares.applyDueSchedules();
        fares.applyDueSchedules(); // idempotente
        JsonNode history = call(HttpMethod.GET, "/api/v1/admin/fares/" + cityId + "_ESSENCIAL/history", admin, null, 200);
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(history.get(0).get("reason").asString()).isEqualTo("Reajuste programado de teste");
        JsonNode current = call(HttpMethod.GET, "/api/v1/admin/cities/" + cityId + "/fares", admin, null, 200).get(0);
        assertThat(current.get("baseFareCents").asLong()).isEqualTo(900);
        assertThat(current.path("scheduled").isMissingNode() || current.path("scheduled").isNull()).isTrue();
    }
}
