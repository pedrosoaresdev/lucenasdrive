package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.security.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

class DemandIT extends IntegrationTestBase {

    private Map<String, Object> policy(boolean enabled) {
        return Map.of("enabled", enabled, "steps", List.of(Map.of("minRatio", 2.0, "multiplierBp", 12_000),
                Map.of("minRatio", 3.0, "multiplierBp", 14_000)), "maxBp", 15_000, "windowMinutes", 10,
                "reason", "Teste de preço dinâmico");
    }

    private JsonNode quote(Actor pax, double dLat) {
        return call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + dLat, LNG, "B")), 200).get("quotes").get(0);
    }

    @Test
    void dynamicPricingIsOptionalCappedTransparentAndHeatmapIsAggregated() {
        Actor admin = staffWithMfa(Role.ADMIN);
        Actor support = staffWithMfa(Role.SUPPORT);
        String cityId = seedCityWithFares(admin, "Cidade Demanda");
        Actor driver = approvedOnlineDriver(admin, cityId, LAT, LNG);
        List<Actor> pax = List.of(passenger(), passenger(), passenger());

        // Desligado por padrão: sem efeito
        JsonNode before = quote(pax.get(0), 0.03);
        assertThat(before.get("highDemand").asBoolean()).isFalse();
        assertThat(call(HttpMethod.GET, "/api/v1/admin/cities/" + cityId + "/demand", admin, null, 200)
                .get("policy").get("enabled").asBoolean()).isFalse();

        // Somente ADMIN/OWNER configuram; política inválida recusada
        call(HttpMethod.PUT, "/api/v1/admin/cities/" + cityId + "/demand-policy", support, policy(true), 403);
        Map<String, Object> bad = new java.util.HashMap<>(policy(true));
        bad.put("maxBp", 40_000);
        call(HttpMethod.PUT, "/api/v1/admin/cities/" + cityId + "/demand-policy", admin, bad, 400);
        call(HttpMethod.PUT, "/api/v1/admin/cities/" + cityId + "/demand-policy", admin, policy(true), 200);

        // 3 passageiros pedindo preço × 1 motorista → razão 3 → 1,4×
        quote(pax.get(1), 0.03);
        quote(pax.get(2), 0.03);
        JsonNode state = call(HttpMethod.POST, "/api/v1/admin/cities/" + cityId + "/demand/recompute", admin, null, 200);
        assertThat(state.get("demand").asLong()).isEqualTo(3);
        assertThat(state.get("supply").asLong()).isEqualTo(1);
        assertThat(state.get("multiplierBp").asLong()).isEqualTo(14_000);
        JsonNode surged = quote(pax.get(0), 0.03);
        assertThat(surged.get("highDemand").asBoolean()).isTrue();
        assertThat(surged.get("multiplierBp").asLong()).isEqualTo(14_000);
        assertThat(surged.get("totalCents").asLong()).isGreaterThan(before.get("totalCents").asLong());

        // Mapa de calor: quadras agregadas, sem identificar passageiros
        JsonNode heat = call(HttpMethod.GET, "/api/v1/driver/demand-heatmap", driver, null, 200);
        assertThat(heat.size()).isEqualTo(1);
        assertThat(heat.get(0).get("count").asLong()).isEqualTo(3);
        assertThat(heat.toString()).doesNotContain(pax.get(0).uid());
        call(HttpMethod.GET, "/api/v1/driver/demand-heatmap", pax.get(0), null, 403);

        // Desligar volta ao preço normal; alterações auditadas
        call(HttpMethod.PUT, "/api/v1/admin/cities/" + cityId + "/demand-policy", admin, policy(false), 200);
        assertThat(quote(pax.get(0), 0.03).get("highDemand").asBoolean()).isFalse();
        JsonNode audit = call(HttpMethod.GET, "/api/v1/admin/audit?targetType=demandPolicy&targetId=" + cityId, admin, null, 200);
        assertThat(audit.get("items").size()).isEqualTo(2);
    }
}
