package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.presence.PresenceService;
import com.lucenadrive.security.Role;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

class PresenceIT extends IntegrationTestBase {

    @Autowired PresenceService presence;

    @Test
    void godEyeShowsOnlyConsentingUsersToAdmins() {
        Actor admin = staffWithMfa(Role.ADMIN);
        Actor support = staffWithMfa(Role.SUPPORT);
        String cityId = seedCityWithFares(admin, "Cidade Olho");
        Actor driver = approvedOnlineDriver(admin, cityId, LAT, LNG);
        Actor pax = passenger();
        Actor quiet = passenger();

        // Sem consentimento: posição recusada
        call(HttpMethod.POST, "/api/v1/me/presence", pax, Map.of("app", "PASSENGER", "lat", LAT, "lng", LNG), 403);
        assertThat(call(HttpMethod.GET, "/api/v1/me/location-sharing", pax, null, 200).get("enabled").asBoolean()).isFalse();

        // Com consentimento
        call(HttpMethod.PUT, "/api/v1/me/location-sharing", pax, Map.of("enabled", true), 200);
        call(HttpMethod.PUT, "/api/v1/me/location-sharing", driver, Map.of("enabled", true), 200);
        call(HttpMethod.POST, "/api/v1/me/presence", pax, Map.of("app", "PASSENGER", "lat", LAT, "lng", LNG, "accuracyM", 12.5), 204);
        call(HttpMethod.POST, "/api/v1/me/presence", driver, Map.of("app", "DRIVER", "lat", LAT + 0.01, "lng", LNG), 204);
        // passageiro não se passa por motorista
        call(HttpMethod.POST, "/api/v1/me/presence", pax, Map.of("app", "DRIVER", "lat", LAT, "lng", LNG), 403);
        call(HttpMethod.POST, "/api/v1/me/presence", pax, Map.of("app", "PASSENGER", "lat", 120, "lng", LNG), 400);

        // Somente ADMIN/OWNER veem o painel
        call(HttpMethod.GET, "/api/v1/admin/god-eye/positions", support, null, 403);
        call(HttpMethod.GET, "/api/v1/admin/god-eye/positions", pax, null, 403);
        JsonNode live = call(HttpMethod.GET, "/api/v1/admin/god-eye/positions", admin, null, 200);
        List<String> ids = live.valueStream().map(n -> n.get("userId").asString()).toList();
        assertThat(ids).contains(pax.uid(), driver.uid()).doesNotContain(quiet.uid());
        JsonNode mine = live.valueStream().filter(n -> n.get("userId").asString().equals(pax.uid())).findFirst().orElseThrow();
        assertThat(mine.get("name").asString()).isEqualTo("Passageira Teste Silva");
        assertThat(mine.get("kind").asString()).isEqualTo("PASSENGER");
        assertThat(call(HttpMethod.GET, "/api/v1/admin/god-eye/positions?kind=DRIVER", admin, null, 200).valueStream()
                .map(n -> n.get("userId").asString()).toList()).contains(driver.uid()).doesNotContain(pax.uid());
        JsonNode audit = call(HttpMethod.GET, "/api/v1/admin/audit?targetType=presence", admin, null, 200);
        assertThat(audit.get("items").valueStream().map(a -> a.get("action").asString())).contains("GOD_EYE_VIEWED");

        // Revogar apaga a posição imediatamente
        call(HttpMethod.PUT, "/api/v1/me/location-sharing", pax, Map.of("enabled", false), 200);
        assertThat(call(HttpMethod.GET, "/api/v1/admin/god-eye/positions", admin, null, 200).valueStream()
                .map(n -> n.get("userId").asString()).toList()).doesNotContain(pax.uid());

        // Posição antiga some do painel e é apagada
        clock.advance(Duration.ofMinutes(6));
        assertThat(call(HttpMethod.GET, "/api/v1/admin/god-eye/positions", admin, null, 200).valueStream()
                .map(n -> n.get("userId").asString()).toList()).doesNotContain(driver.uid());
        clock.advance(Duration.ofMinutes(30));
        presence.purge();
        assertThat(store.get(com.lucenadrive.persistence.Collections.PRESENCE, driver.uid(), Map.class)).isEmpty();
    }
}
