package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.payment.webhook.WebhookSignature;
import com.lucenadrive.security.Role;
import com.lucenadrive.support.FakePaymentProvider;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/** Pagamento digital ponta a ponta com provedor FICTÍCIO de teste: webhooks, idempotência, saques. */
@Import(PaymentsIT.ProviderConfig.class)
@TestPropertySource(properties = {"lucena.payments.provider=fake", "lucena.payments.api-key=test",
        "lucena.payments.webhook-secret=whsec_test"})
class PaymentsIT extends IntegrationTestBase {

    @TestConfiguration
    static class ProviderConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        FakePaymentProvider fakePaymentProvider() {
            return new FakePaymentProvider();
        }
    }

    @Autowired FakePaymentProvider provider;

    private int webhook(String body, String signature) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var req = MockMvcRequestBuilders.post("/api/v1/webhooks/payments/fake").contentType(MediaType.APPLICATION_JSON)
                .content(bytes);
        if (signature != null) {
            req.header("X-Lucena-Signature", signature);
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    private int signedWebhook(String body) throws Exception {
        return webhook(body, WebhookSignature.sign("whsec_test", body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void pixRideConfirmedOnlyByAuthenticWebhookAndPayoutFlow() throws Exception {
        Actor admin = staffWithMfa(Role.ADMIN);
        String cityId = seedCityWithFares(admin, "Cidade Pagamentos");
        Actor driver = approvedOnlineDriver(admin, cityId, LAT, LNG);
        Actor pax = passenger();
        JsonNode est = call(HttpMethod.POST, "/api/v1/rides/estimate", pax, Map.of("origin", place(LAT, LNG, "A"),
                "destination", place(LAT + 0.04, LNG, "B")), 200);
        String rideId = call(HttpMethod.POST, "/api/v1/rides", pax, Map.of("quoteId",
                est.get("quotes").get(0).get("quoteId").asString(), "paymentMethod", "PIX"), 201, idem()).get("id").asString();
        call(HttpMethod.POST, "/api/v1/driver/offers/" + rideId + "/accept", driver, null, 200);
        driverAt(driver, LAT, LNG);
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/status", driver, Map.of("status", "DRIVER_ARRIVED"), 200);
        call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/status", driver, Map.of("status", "IN_PROGRESS"), 200);
        JsonNode done = call(HttpMethod.POST, "/api/v1/driver/rides/" + rideId + "/status", driver,
                Map.of("status", "COMPLETED"), 200);
        // Concluída, mas pagamento NÃO é marcado como confirmado sem o provedor
        assertThat(done.get("paymentStatus").asString()).isEqualTo("AWAITING_PROVIDER");
        assertThat(provider.charges).hasSize(1);
        long earning = done.get("fare").get("driverEarningCents").asLong();

        String confirmed = "{\"id\":\"evt_1\",\"type\":\"PAYMENT_CONFIRMED\",\"data\":{\"chargeId\":\"ch_1\",\"feeCents\":50}}";
        assertThat(webhook(confirmed, null)).isEqualTo(401);
        assertThat(webhook(confirmed, "sha256=deadbeef")).isEqualTo(401);
        assertThat(signedWebhook(confirmed)).isEqualTo(200);
        assertThat(signedWebhook(confirmed)).isEqualTo(200); // duplicado: ignorado
        JsonNode detail = call(HttpMethod.GET, "/api/v1/admin/rides/" + rideId, admin, null, 200);
        assertThat(detail.get("ride").get("paymentStatus").asString()).isEqualTo("CONFIRMED");
        long feeEntries = detail.get("ledger").valueStream().filter(e -> e.get("type").asString().equals("PROVIDER_FEE")).count();
        assertThat(feeEntries).isEqualTo(1);

        // Antes da liquidação nada está disponível para saque
        call(HttpMethod.PUT, "/api/v1/drivers/me/finance/pix-key", driver, Map.of("keyType", "EMAIL",
                "key", "motorista@exemplo.test"), 200);
        call(HttpMethod.POST, "/api/v1/drivers/me/finance/payouts", driver, Map.of("amountCents", earning), 422, idem());

        assertThat(signedWebhook("{\"id\":\"evt_2\",\"type\":\"PAYMENT_SETTLED\",\"data\":{\"chargeId\":\"ch_1\"}}"))
                .isEqualTo(200);
        JsonNode fin = call(HttpMethod.GET, "/api/v1/drivers/me/finance", driver, null, 200);
        assertThat(fin.get("withdrawableCents").asLong()).isEqualTo(earning);

        // Saque acima do disponível recusado; dentro do disponível vai ao provedor
        call(HttpMethod.POST, "/api/v1/drivers/me/finance/payouts", driver, Map.of("amountCents", earning + 1), 422, idem());
        JsonNode payout = call(HttpMethod.POST, "/api/v1/drivers/me/finance/payouts", driver, Map.of("amountCents", earning),
                201, idem());
        assertThat(payout.get("status").asString()).isEqualTo("PROCESSING");
        assertThat(payout.get("pixKeyMasked").asString()).isEqualTo("m***@exemplo.test");
        assertThat(provider.transfers.get(0).pixKey()).isEqualTo("motorista@exemplo.test");
        // Saldo comprometido pelo saque em curso
        call(HttpMethod.POST, "/api/v1/drivers/me/finance/payouts", driver, Map.of("amountCents", 1000), 422, idem());

        assertThat(signedWebhook("{\"id\":\"evt_3\",\"type\":\"TRANSFER_COMPLETED\",\"data\":{\"transferId\":\"tr_1\","
                + "\"receiptUrl\":\"https://provedor.test/comprovante/1\"}}")).isEqualTo(200);
        JsonNode payouts = call(HttpMethod.GET, "/api/v1/drivers/me/finance/payouts", driver, null, 200);
        assertThat(payouts.get(0).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(payouts.get(0).get("receiptUrl").asString()).contains("comprovante");

        // Saque da plataforma: somente OWNER/FINANCE com MFA recente, limitado ao saldo do provedor
        Actor owner = staffWithMfa(Role.OWNER);
        call(HttpMethod.PUT, "/api/v1/admin/finance/pix-key", owner, Map.of("keyType", "EVP",
                "key", "123e4567-e89b-12d3-a456-426614174000"), 200);
        call(HttpMethod.POST, "/api/v1/admin/finance/withdrawals", owner, Map.of("amountCents", 200_000), 422, idem());
        JsonNode w = call(HttpMethod.POST, "/api/v1/admin/finance/withdrawals", owner, Map.of("amountCents", 50_000), 200, idem());
        assertThat(w.get("ownerType").asString()).isEqualTo("PLATFORM");
        call(HttpMethod.POST, "/api/v1/admin/finance/withdrawals", admin, Map.of("amountCents", 1_000), 403, idem());
        JsonNode audit = call(HttpMethod.GET, "/api/v1/admin/audit?targetType=pixKey", owner, null, 200);
        assertThat(audit.get("items").size()).isGreaterThanOrEqualTo(1);
    }
}
