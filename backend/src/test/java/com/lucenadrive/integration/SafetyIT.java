package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.security.Role;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

class SafetyIT extends IntegrationTestBase {

    @Test
    void tripSharingAndSafetyAlert() {
        Actor admin = staffWithMfa(Role.ADMIN);
        Actor support = staffWithMfa(Role.SUPPORT);
        String cityId = seedCityWithFares(admin, "Cidade Segurança");
        Actor driver = approvedOnlineDriver(admin, cityId, LAT, LNG);
        Actor pax = passenger();
        Actor stranger = passenger();
        JsonNode est = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "Rua A, 1"),
                "destination", place(LAT + 0.03, LNG, "Rua B, 2")), 200);
        String rideId = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId",
                est.get("quotes").get(0).get("quoteId").asString(), "paymentMethod", "CASH"), 201, idem()).get("id").asString();
        call(HttpMethod.POST, "/api/v1/driver/offers/" + rideId + "/accept", driver, null, 200);
        driverAt(driver, LAT + 0.004, LNG);

        // Só o passageiro da corrida compartilha
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/share", stranger, null, 403);
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/share", driver, null, 403);
        String token = call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/share", pax, null, 201).get("token").asString();

        // Link público mostra o mínimo necessário, sem autenticação
        JsonNode view = call(HttpMethod.GET, "/api/v1/public/ride-shares/" + token, null, null, 200);
        assertThat(view.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(view.get("plate").asString()).isNotBlank();
        assertThat(view.get("driverPosition").get("lat").asDouble()).isEqualTo(LAT + 0.004);
        assertThat(view.toString()).doesNotContain(pax.uid()).doesNotContain(driver.uid()).doesNotContain("Silva");
        call(HttpMethod.GET, "/api/v1/public/ride-shares/token-invalido-qualquer-123", null, null, 404);

        // Alerta de segurança: chamado urgente com a última posição; terceiros não podem acionar
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/safety-alert", stranger, Map.of(), 403);
        JsonNode ticket = call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/safety-alert", pax,
                Map.of("description", "Motorista mudou a rota sem explicação."), 201);
        assertThat(ticket.get("priority").asString()).isEqualTo("URGENT");
        assertThat(ticket.get("category").asString()).isEqualTo("SAFETY");
        assertThat(ticket.get("metadata").get("lastLat").asString()).isNotBlank();
        List<String> urgent = call(HttpMethod.GET, "/api/v1/admin/support/tickets?status=OPEN&category=SAFETY", support, null, 200)
                .valueStream().map(t -> t.get("priority").asString()).toList();
        assertThat(urgent).contains("URGENT");

        // Revogação e expiração após o fim da corrida
        call(HttpMethod.DELETE, "/api/v1/rides/" + rideId + "/share", pax, null, 204);
        call(HttpMethod.GET, "/api/v1/public/ride-shares/" + token, null, null, 404);
        String token2 = call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/share", pax, null, 201).get("token").asString();
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/cancel", pax, Map.of("reasonCode", "CHANGED_MIND"), 200);
        assertThat(call(HttpMethod.GET, "/api/v1/public/ride-shares/" + token2, null, null, 200)
                .get("status").asString()).isEqualTo("CANCELLED");
        clock.advance(Duration.ofMinutes(61));
        call(HttpMethod.GET, "/api/v1/public/ride-shares/" + token2, null, null, 404);
    }
}
