package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.security.Role;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

/**
 * Critério de aceitação ponta a ponta (backend): cadastro, aprovação, cotação, cupom, solicitação,
 * oferta, aceite, chat, progresso, conclusão, avaliação, média, painel, ledger e histórico tarifário.
 */
class RideLifecycleIT extends IntegrationTestBase {

    @Test
    void fullRideFlow() {
        Actor admin = staffWithMfa(Role.ADMIN);
        String cityId = seedCityWithFares(admin, "Cidade Fluxo");
        Actor driver = approvedOnlineDriver(admin, cityId, LAT + 0.01, LNG);
        Actor pax = passenger();

        // Cupom criado pelo admin
        call(HttpMethod.POST, "/api/v1/admin/coupons", admin, Map.ofEntries(Map.entry("code", "bem-vindo5"),
                Map.entry("description", "Primeira viagem"), Map.entry("type", "FIXED"), Map.entry("amountCents", 500),
                Map.entry("maxRedemptions", 100), Map.entry("maxPerUser", 1), Map.entry("newPassengersOnly", true),
                Map.entry("active", true)), 201);

        // 4–6: cotação com cupom
        JsonNode estimate = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of(
                "origin", place(LAT, LNG, "Origem teste"), "destination", place(LAT + 0.05, LNG + 0.05, "Destino teste"),
                "couponCode", "BEMVINDO5"), 200);
        assertThat(estimate.get("approximateRoute").asBoolean()).isTrue(); // sem chave Google: estimativa explícita
        JsonNode quote = estimate.get("quotes").get(0);
        assertThat(quote.get("category").asString()).isEqualTo("ESSENCIAL");
        assertThat(quote.get("discountCents").asLong()).isEqualTo(500);
        assertThat(quote.get("availableDrivers").asLong()).isEqualTo(1);
        long total = quote.get("totalCents").asLong();

        // Pix sem provedor: recusado (não simulado)
        call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId", quote.get("quoteId").asString(),
                "paymentMethod", "PIX"), 503, idem());

        // 6: solicitação idempotente
        Map<String, String> key = idem();
        JsonNode ride = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId", quote.get("quoteId").asString(),
                "paymentMethod", "CASH"), 201, key);
        JsonNode again = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId", quote.get("quoteId").asString(),
                "paymentMethod", "CASH"), 201, key);
        String rideId = ride.get("id").asString();
        assertThat(again.get("id").asString()).isEqualTo(rideId);
        assertThat(ride.get("status").asString()).isEqualTo("SEARCHING_DRIVER");
        assertThat(ride.get("fare").get("finalCents").asLong()).isEqualTo(total - 500);
        assertThat(ride.get("fare").has("commissionCents") && !ride.get("fare").get("commissionCents").isNull()).isFalse();

        // Segunda corrida simultânea bloqueada
        JsonNode est2 = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.02, LNG, "B")), 200);
        call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId", est2.get("quotes").get(0).get("quoteId").asString(),
                "paymentMethod", "CASH"), 409, idem());

        // 7: motorista recebe e aceita a oferta
        JsonNode offers = call(HttpMethod.GET, "/api/v1/driver/offers", driver, null, 200);
        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).get("rideId").asString()).isEqualTo(rideId);
        JsonNode accepted = call(HttpMethod.POST, "/api/v1/driver/offers/" + rideId + "/accept", driver, null, 200);
        assertThat(accepted.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("driver").get("plate").asString()).startsWith("TST");

        // 8: localização em tempo real refletida na corrida do passageiro
        call(HttpMethod.POST, "/api/v1/driver/location", driver, Map.of("lat", LAT + 0.005, "lng", LNG, "heading", 90.0), 204);
        JsonNode paxView = call(HttpMethod.GET, "/api/v1/rides/" + rideId, pax, null, 200);
        assertThat(paxView.get("driverPosition").get("lat").asDouble()).isEqualTo(LAT + 0.005);
        assertThat(paxView.get("status").asString()).isEqualTo("ACCEPTED");

        // 9–10: chat em tempo real (envio pelo backend; mensagens de sistema geradas pelo servidor)
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/chat/messages", pax,
                Map.of("clientMessageId", UUID.randomUUID().toString(), "text", "Estou no portão azul"), 201);
        clock.advance(Duration.ofSeconds(1));
        String dupId = UUID.randomUUID().toString();
        JsonNode m1 = call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/chat/messages", driver,
                Map.of("clientMessageId", dupId, "text", "Chego em 2 minutos"), 201);
        JsonNode m1again = call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/chat/messages", driver,
                Map.of("clientMessageId", dupId, "text", "Chego em 2 minutos"), 201);
        assertThat(m1again.get("id").asString()).isEqualTo(m1.get("id").asString());
        JsonNode history = call(HttpMethod.GET, "/api/v1/rides/" + rideId + "/chat/messages", pax, null, 200);
        List<String> texts = history.get("items").valueStream().map(n -> n.get("text").asString()).toList();
        assertThat(texts).contains("Estou no portão azul", "Chego em 2 minutos", "Motorista aceitou a corrida.");
        assertThat(texts.stream().filter("Chego em 2 minutos"::equals).count()).isEqualTo(1);
        assertThat(history.get("items").get(0).get("senderRole").asString()).isEqualTo("DRIVER");

        // Isolamento do chat: terceiro não lê nem envia
        Actor intruder = passenger();
        call(HttpMethod.GET, "/api/v1/rides/" + rideId + "/chat/messages", intruder, null, 403);
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/chat/messages", intruder,
                Map.of("clientMessageId", UUID.randomUUID().toString(), "text", "oi"), 403);
        call(HttpMethod.GET, "/api/v1/rides/" + rideId, intruder, null, 403);

        // Passageiro não pode avaliar antes da conclusão
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/rating", pax, Map.of("stars", 5), 422);
        // Transição inválida rejeitada
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/status", driver, Map.of("status", "COMPLETED"), 409);

        // 11: progresso e conclusão
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/status", driver, Map.of("status", "DRIVER_ARRIVING"), 200);
        // "Cheguei" exige posição recente perto do embarque
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/status", driver, Map.of("status", "DRIVER_ARRIVED"), 422);
        driverAt(driver, LAT + 0.02, LNG);
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/status", driver, Map.of("status", "DRIVER_ARRIVED"), 422);
        driverAt(driver, LAT + 0.001, LNG);
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/status", driver, Map.of("status", "DRIVER_ARRIVED"), 200);
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/status", driver, Map.of("status", "IN_PROGRESS"), 200);
        JsonNode done = call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/status", driver,
                Map.of("status", "COMPLETED"), 200);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("paymentStatus").asString()).isEqualTo("CASH_DECLARED");

        // Chat fica somente leitura
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/chat/messages", pax,
                Map.of("clientMessageId", UUID.randomUUID().toString(), "text", "obrigada"), 409);

        // 12: avaliação pendente sobrevive ao "fechar o app"
        JsonNode pending = call(HttpMethod.GET, "/api/v1/rides/pending-rating", pax, null, 200);
        assertThat(pending.get("id").asString()).isEqualTo(rideId);

        // Estrelas fora da escala rejeitadas
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/rating", pax, Map.of("stars", 6), 400);
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/rating", pax, Map.of("stars", 0), 400);
        // Motorista não avalia a si mesmo pelo fluxo do passageiro
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/rating", driver, Map.of("stars", 5), 403);

        // 13–14: avaliação e média
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/rating", pax, Map.of("stars", 4,
                "comment", "Ótima viagem", "tags", List.of("DIRECAO_SEGURA", "CORDIALIDADE")), 201);
        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/rating", pax, Map.of("stars", 1), 409);
        call(HttpMethod.GET, "/api/v1/rides/pending-rating", pax, null, 204);
        JsonNode summary = call(HttpMethod.GET, "/api/v1/drivers/" + driver.uid() + "/ratings/summary", pax, null, 200);
        assertThat(summary.get("average").asDouble()).isEqualTo(4.0);
        assertThat(summary.get("count").asLong()).isEqualTo(1);

        // Avaliação recíproca: motorista avalia o passageiro (uma vez; só o motorista da corrida)
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/passenger-rating", driver,
                Map.of("stars", 5, "tags", List.of("DIRECAO_SEGURA")), 400);
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/passenger-rating", pax, Map.of("stars", 5), 403);
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/passenger-rating", driver,
                Map.of("stars", 5, "tags", List.of("EDUCADO", "PONTUAL")), 201);
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/passenger-rating", driver, Map.of("stars", 3), 409);
        JsonNode paxRating = call(HttpMethod.GET, "/api/v1/passengers/me/rating", pax, null, 200);
        assertThat(paxRating.get("average").asDouble()).isEqualTo(5.0);
        assertThat(paxRating.get("count").asLong()).isEqualTo(1);
        // a nota individual do motorista não fica no documento da corrida (que o passageiro lê)
        JsonNode paxAfter = call(HttpMethod.GET, "/api/v1/rides/" + rideId, pax, null, 200);
        assertThat(paxAfter.path("driverRating").isNull() || paxAfter.path("driverRating").isMissingNode()).isTrue();
        // a avaliação do passageiro não entra na média do motorista
        assertThat(call(HttpMethod.GET, "/api/v1/drivers/" + driver.uid() + "/ratings/summary", pax, null, 200)
                .get("count").asLong()).isEqualTo(1);

        // 15–16: painel administrativo, comissão e lançamentos
        JsonNode detail = call(HttpMethod.GET, "/api/v1/admin/rides/" + rideId, admin, null, 200);
        assertThat(detail.get("ride").get("fare").get("commissionCents").asLong()).isEqualTo(Math.round(total * 0.2));
        List<String> types = detail.get("ledger").valueStream().map(n -> n.get("type").asString()).toList();
        assertThat(types).containsExactlyInAnyOrder("RIDE_FARE_CASH", "PLATFORM_COMMISSION", "DRIVER_COMMISSION_DUE",
                "COUPON_SUBSIDY", "COUPON_SUBSIDY");
        assertThat(detail.get("events").size()).isGreaterThanOrEqualTo(7);
        assertThat(detail.get("rating").get("stars").asInt()).isEqualTo(4);
        assertThat(detail.get("passengerRating").get("stars").asInt()).isEqualTo(5);
        JsonNode dash = call(HttpMethod.GET, "/api/v1/admin/dashboard/summary", admin, null, 200);
        assertThat(dash.get("ridesCompleted").asLong()).isEqualTo(1);
        assertThat(dash.get("finance").get("commissionCalculatedCents").asLong()).isEqualTo(Math.round(total * 0.2));
        assertThat(dash.get("finance").get("commissionReceivedCents").asLong()).isZero(); // nada liquidado
        assertThat(dash.get("finance").get("providerConfigured").asBoolean()).isFalse();
        JsonNode coupon = call(HttpMethod.GET, "/api/v1/admin/coupons/BEMVINDO5/results", admin, null, 200);
        assertThat(coupon.get("confirmedRedemptions").asLong()).isEqualTo(1);

        // Cupom de primeira viagem não vale mais para este passageiro
        JsonNode est3 = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.02, LNG, "B"), "couponCode", "BEMVINDO5"), 200);
        assertThat(est3.get("quotes").get(0).get("discountCents").asLong()).isZero();

        // 18: alteração tarifária preserva histórico e não altera corrida anterior
        putFare(admin, cityId, "ESSENCIAL", 900, 400, 2500, "Reajuste de combustível");
        JsonNode hist = call(HttpMethod.GET, "/api/v1/admin/fares/" + cityId + "_ESSENCIAL/history", admin, null, 200);
        assertThat(hist).hasSize(2);
        assertThat(hist.get(0).get("previous").get("baseFareCents").asLong()).isEqualTo(500);
        assertThat(hist.get(0).get("changedBy").asString()).isEqualTo(admin.uid());
        JsonNode oldRide = call(HttpMethod.GET, "/api/v1/rides/" + rideId, pax, null, 200);
        assertThat(oldRide.get("fare").get("totalCents").asLong()).isEqualTo(total);
        assertThat(oldRide.get("fare").get("fareConfigVersion").asInt()).isEqualTo(1);

        // Histórico do passageiro e do motorista
        JsonNode paxHistory = call(HttpMethod.GET, "/api/v1/rides?as=passenger", pax, null, 200);
        assertThat(paxHistory.get("items")).hasSize(1);
        JsonNode drvHistory = call(HttpMethod.GET, "/api/v1/rides?as=driver", driver, null, 200);
        assertThat(drvHistory.get("items").get(0).get("fare").get("driverEarningCents").asLong()).isPositive();

        // Auditoria do acesso administrativo ao chat
        call(HttpMethod.GET, "/api/v1/admin/rides/" + rideId + "/chat?reason=curto", admin, null, 400);
        call(HttpMethod.GET, "/api/v1/admin/rides/" + rideId + "/chat?reason=Apuracao%20de%20reclamacao%20formal", admin, null, 200);
        JsonNode audit = call(HttpMethod.GET, "/api/v1/admin/audit?targetId=" + rideId, admin, null, 200);
        assertThat(audit.get("items").valueStream().map(n -> n.get("action").asString())).contains("ADMIN_CHAT_ACCESS");
    }

    @Test
    void offerExpiresAndPassengerCancellationAfterArrivalChargesFee() {
        Actor admin = staffWithMfa(Role.ADMIN);
        String cityId = seedCityWithFares(admin, "Cidade Cancelamento");
        Actor driver = approvedOnlineDriver(admin, cityId, LAT, LNG + 0.01);
        Actor pax = passenger();
        JsonNode est = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.03, LNG, "B")), 200);
        String quoteId = est.get("quotes").get(0).get("quoteId").asString();
        String rideId = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId", quoteId, "paymentMethod", "CASH"),
                201, idem()).get("id").asString();

        // Oferta expira
        clock.advance(Duration.ofSeconds(25));
        call(HttpMethod.POST, "/api/v1/driver/offers/" + rideId + "/accept", driver, null, 410);

        // Cotação reutilizada é recusada (uso único)
        call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId", quoteId, "paymentMethod", "CASH"), 410, idem());

        call(HttpMethod.POST, "/api/v1/rides/" + rideId + "/cancel", pax, Map.of("reasonCode", "CHANGED_MIND"), 200);

        // Nova corrida: aceita, chega e passageiro cancela → taxa registrada (pendente)
        est = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.03, LNG, "B")), 200);
        String ride2 = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId",
                est.get("quotes").get(0).get("quoteId").asString(), "paymentMethod", "CASH"), 201, idem()).get("id").asString();
        call(HttpMethod.POST, "/api/v1/driver/offers/" + ride2 + "/accept", driver, null, 200);
        driverAt(driver, LAT, LNG);
        call(HttpMethod.POST, "/api/v1/driver/rides/" + ride2 + "/status", driver, Map.of("status", "DRIVER_ARRIVED"), 200);
        JsonNode cancelled = call(HttpMethod.POST, "/api/v1/rides/" + ride2 + "/cancel", pax,
                Map.of("reasonCode", "TOO_LONG"), 200);
        assertThat(cancelled.get("cancellation").get("feeCents").asLong()).isEqualTo(700);
        // Corrida cancelada não pode ser avaliada
        call(HttpMethod.POST, "/api/v1/rides/" + ride2 + "/rating", pax, Map.of("stars", 3), 422);
        // Motorista foi liberado
        JsonNode me = call(HttpMethod.GET, "/api/v1/drivers/me", driver, null, 200);
        assertThat(me.get("currentRideId").isNull()).isTrue();
    }
}
