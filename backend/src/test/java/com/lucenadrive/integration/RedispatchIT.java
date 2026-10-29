package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.persistence.Collections;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.ride.RideDispatch;
import com.lucenadrive.security.Role;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

class RedispatchIT extends IntegrationTestBase {

    @Test
    void driverCancellationBeforePickupReturnsRideToSearchWithoutOldChat() {
        Actor admin = staffWithMfa(Role.ADMIN);
        String cityId = seedCityWithFares(admin, "Cidade Redespacho");
        Actor d1 = approvedOnlineDriver(admin, cityId, LAT, LNG);
        Actor d2 = approvedOnlineDriver(admin, cityId, LAT + 0.001, LNG);
        Actor d3 = approvedOnlineDriver(admin, cityId, LAT + 0.002, LNG);
        Actor pax = passenger();

        JsonNode est = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.03, LNG, "B")), 200);
        String rideId = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId",
                est.get("quotes").get(0).get("quoteId").asString(), "paymentMethod", "CASH"), 201, idem()).get("id").asString();

        // 1º motorista aceita, conversa e desiste antes do embarque
        call(HttpMethod.POST, "/api/v1/driver/offers/" + rideId + "/accept", d1, null, 200);
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/chat/messages", pax,
                Map.of("clientMessageId", UUID.randomUUID().toString(), "text", "Mensagem para o primeiro motorista"), 201);
        JsonNode afterCancel = call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/cancel", d1,
                Map.of("reasonCode", "VEHICLE_PROBLEM"), 200);
        assertThat(afterCancel.get("status").asString()).isEqualTo("SEARCHING_DRIVER");

        Ride ride = store.get(Collections.RIDES, rideId, Ride.class).orElseThrow();
        assertThat(ride.driverId()).isNull();
        assertThat(ride.participantIds()).containsExactly(pax.uid());
        assertThat(ride.cancellation()).isNull();
        RideDispatch disp = store.get(Collections.RIDE_DISPATCH, rideId, RideDispatch.class).orElseThrow();
        assertThat(disp.redispatches()).isEqualTo(1);
        assertThat(disp.excludedDriverIds()).containsExactly(d1.uid());

        // quem desistiu não volta a receber a corrida nem lê mais nada dela
        call(HttpMethod.POST, "/api/v1/driver/offers/" + rideId + "/accept", d1, null, 410);
        call(HttpMethod.GET, "/api/v1/rides/" + rideId, d1, null, 403);

        // nova busca: outro motorista aceita e não vê a conversa anterior
        Actor next = acceptByAny(rideId, d2, d3);
        JsonNode chat = call(HttpMethod.GET, "/api/v1/rides/" + rideId + "/chat/messages", next, null, 200);
        assertThat(chat.toString()).doesNotContain("primeiro motorista");
        JsonNode archives = call(HttpMethod.GET, "/api/v1/admin/rides/" + rideId
                + "/chat-archives?reason=Apuracao de reclamacao do passageiro", admin, null, 200);
        assertThat(archives.toString()).contains("primeiro motorista");

        // 2ª desistência ainda redespacha; a 3ª cancela a corrida
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/cancel", next, Map.of("reasonCode", "OTHER"), 200);
        Actor last = next == d2 ? d3 : d2;
        call(HttpMethod.POST, "/api/v1/driver/offers/" + rideId + "/accept", last, null, 200);
        JsonNode third = call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/cancel", last,
                Map.of("reasonCode", "OTHER"), 200);
        assertThat(third.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(store.get(Collections.RIDES, rideId, Ride.class).orElseThrow().cancellation().feeCents()).isZero();
    }

    private Actor acceptByAny(String rideId, Actor... drivers) {
        for (Actor d : drivers) {
            JsonNode offers = call(HttpMethod.GET, "/api/v1/driver/offers", d, null, 200);
            if (offers.toString().contains(rideId)) {
                call(HttpMethod.POST, "/api/v1/driver/offers/" + rideId + "/accept", d, null, 200);
                return d;
            }
        }
        throw new AssertionError("nenhum motorista recebeu a nova oferta");
    }
}
