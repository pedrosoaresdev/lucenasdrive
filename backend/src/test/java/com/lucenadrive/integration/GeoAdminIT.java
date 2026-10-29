package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.security.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

class GeoAdminIT extends IntegrationTestBase {

    @Test
    void adminEditsServiceAreaAndCityWithAudit() {
        Actor admin = staffWithMfa(Role.ADMIN);
        Actor finance = staffWithMfa(Role.FINANCE);
        String cityId = seedCityWithFares(admin, "Cidade Geo");
        JsonNode area = call(HttpMethod.GET, "/api/v1/admin/cities/" + cityId + "/areas", admin, null, 200).get(0);
        String areaId = area.get("id").asString();

        List<Map<String, Double>> smaller = List.of(Map.of("lat", LAT - 0.05, "lng", LNG - 0.05),
                Map.of("lat", LAT - 0.05, "lng", LNG + 0.05), Map.of("lat", LAT + 0.05, "lng", LNG + 0.05),
                Map.of("lat", LAT + 0.05, "lng", LNG - 0.05));
        Map<String, Object> body = Map.of("cityId", cityId, "name", "Cobertura centro", "type", "COVERAGE",
                "polygon", smaller, "active", true, "notes", "Ajuste do limite norte", "reason", "Redesenho da cobertura");
        call(HttpMethod.PUT, "/api/v1/admin/areas/" + areaId, finance, body, 403);
        JsonNode updated = call(HttpMethod.PUT, "/api/v1/admin/areas/" + areaId, admin, body, 200);
        assertThat(updated.get("name").asString()).isEqualTo("Cobertura centro");
        assertThat(updated.get("polygon").size()).isEqualTo(4);

        // a nova cobertura vale para cotações: fora dela não há corrida
        Actor pax = passenger();
        call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.15, LNG, "B")), 422);
        call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.03, LNG, "B")), 200);

        JsonNode city = call(HttpMethod.PUT, "/api/v1/admin/cities/" + cityId, admin, Map.of("name", "Cidade Geo Nova",
                "state", "pe", "center", Map.of("lat", LAT, "lng", LNG), "active", true, "reason", "Correção do nome"), 200);
        assertThat(city.get("name").asString()).isEqualTo("Cidade Geo Nova");
        assertThat(city.get("state").asString()).isEqualTo("PE");
        JsonNode audit = call(HttpMethod.GET, "/api/v1/admin/audit?targetType=serviceArea&targetId=" + areaId, admin, null, 200);
        assertThat(audit.get("items").valueStream().map(a -> a.get("action").asString())).contains("SERVICE_AREA_UPDATED");
    }
}
