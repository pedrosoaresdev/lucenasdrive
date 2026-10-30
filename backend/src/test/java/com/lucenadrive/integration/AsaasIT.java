package com.lucenadrive.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.security.Role;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador Asaas contra um servidor local que reproduz a API v3 (o sandbox real exige a chave do
 * titular). Cobre: split automático para a carteira do motorista, recusa do split com cobrança sem
 * split, QR Pix para o passageiro, webhook autenticado por token, eventos de outras cobranças
 * ignorados e conciliação ativa consultando o provedor.
 */
class AsaasIT extends IntegrationTestBase {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String TOKEN = "whtok_teste_asaas";
    static final String INVALID_WALLET = "00000000-0000-0000-0000-000000000000";
    static final List<JsonNode> createdPayments = new CopyOnWriteArrayList<>();
    static final Map<String, String> statusById = new ConcurrentHashMap<>();
    static final AtomicInteger seq = new AtomicInteger();
    static final AtomicInteger customersCreated = new AtomicInteger();
    static final HttpServer ASAAS = start();

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", AsaasIT::handle);
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getQuery();
        if (!"key_sandbox_teste".equals(ex.getRequestHeaders().getFirst("access_token"))) {
            reply(ex, 401, "{\"errors\":[{\"description\":\"chave inválida\"}]}");
            return;
        }
        if (method.equals("GET") && path.equals("/payments")) {
            String ref = query.replaceAll(".*externalReference=([^&]+).*", "$1");
            var arr = JSON.createArrayNode();
            createdPayments.stream().filter(p -> p.path("externalReference").asString().equals(ref)).forEach(arr::add);
            reply(ex, 200, "{\"data\":" + arr + "}");
        } else if (method.equals("GET") && path.equals("/customers")) {
            reply(ex, 200, "{\"data\":[]}");
        } else if (method.equals("POST") && path.equals("/customers")) {
            JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
            boolean ok = body.path("cpfCnpj").asString().matches("\\d{11}");
            reply(ex, ok ? 200 : 400, ok ? "{\"id\":\"cus_" + customersCreated.incrementAndGet() + "\"}"
                    : "{\"errors\":[{\"description\":\"CPF inválido\"}]}");
        } else if (method.equals("POST") && path.equals("/payments")) {
            var body = (tools.jackson.databind.node.ObjectNode) JSON.readTree(ex.getRequestBody().readAllBytes());
            if (body.path("split").path(0).path("walletId").asString().equals(INVALID_WALLET)) {
                reply(ex, 400, "{\"errors\":[{\"code\":\"invalid_split\",\"description\":\"Carteira não encontrada\"}]}");
                return;
            }
            String id = "pay_" + seq.incrementAndGet();
            body.put("id", id).put("status", "PENDING").put("invoiceUrl", "https://sandbox.asaas.test/i/" + id);
            createdPayments.add(body);
            statusById.put(id, "PENDING");
            reply(ex, 200, body.toString());
        } else if (method.equals("GET") && path.matches("/payments/[^/]+/pixQrCode")) {
            reply(ex, 200, "{\"payload\":\"00020126580014br.gov.bcb.pix-teste\",\"encodedImage\":\"iVBORw0KGgo=\"}");
        } else if (method.equals("GET") && path.startsWith("/payments/")) {
            String id = path.substring("/payments/".length());
            JsonNode p = createdPayments.stream().filter(x -> x.path("id").asString().equals(id)).findFirst().orElseThrow();
            double value = p.path("value").asDouble();
            reply(ex, 200, "{\"id\":\"" + id + "\",\"status\":\"" + statusById.get(id) + "\",\"value\":" + value
                    + ",\"netValue\":" + (value - 0.99) + "}");
        } else {
            reply(ex, 404, "{}");
        }
    }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @DynamicPropertySource
    static void asaas(DynamicPropertyRegistry r) {
        r.add("lucena.payments.provider", () -> "asaas");
        r.add("lucena.payments.api-key", () -> "key_sandbox_teste");
        r.add("lucena.payments.webhook-secret", () -> TOKEN);
        r.add("lucena.payments.base-url", () -> "http://127.0.0.1:" + ASAAS.getAddress().getPort());
        r.add("lucena.payments.sync-ms", () -> "3600000");
    }

    @AfterAll
    static void stop() {
        ASAAS.stop(0);
    }

    private int webhook(String body, String token) throws Exception {
        var req = MockMvcRequestBuilders.post("/api/v1/webhooks/payments/asaas").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (token != null) {
            req.header("asaas-access-token", token);
        }
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    /** Corrida Pix concluída; devolve {rideId, driverEarningCents, finalCents}. */
    private long[] completedPixRide(Actor driver, Actor pax, String[] rideIdOut) {
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
        assertThat(done.get("paymentStatus").asString()).isEqualTo("AWAITING_PROVIDER");
        rideIdOut[0] = rideId;
        return new long[] {done.get("fare").get("driverEarningCents").asLong(), done.get("fare").get("finalCents").asLong()};
    }

    private JsonNode payment(String rideId) {
        return createdPayments.stream().filter(p -> p.path("externalReference").asString().equals(rideId)).findFirst()
                .orElseThrow();
    }

    @Test
    void splitChargeWebhookAndActiveReconciliation() throws Exception {
        Actor admin = staffWithMfa(Role.ADMIN);
        String cityId = seedCityWithFares(admin, "Cidade Asaas");

        // ---- 1) Motorista com carteira vinculada: split automático da parte dele
        Actor driver = approvedOnlineDriver(admin, cityId, LAT, LNG);
        JsonNode status = call(HttpMethod.GET, "/api/v1/drivers/me/finance/split-account", driver, null, 200);
        assertThat(status.get("provider").asString()).isEqualTo("asaas");
        assertThat(status.get("splitSupported").asBoolean()).isTrue();
        call(HttpMethod.PUT, "/api/v1/drivers/me/finance/split-account", driver, Map.of("walletId", "carteira-invalida"), 400);
        JsonNode linked = call(HttpMethod.PUT, "/api/v1/drivers/me/finance/split-account", driver,
                Map.of("walletId", "7BAF1A2C-1111-4222-8333-944455556666"), 200);
        assertThat(linked.get("walletMasked").asString()).isEqualTo("7baf••••-••••-••••-••••••••6666");

        Actor pax = passenger();
        String[] ride = new String[1];
        long[] v = completedPixRide(driver, pax, ride);
        long earning = v[0];
        long total = v[1];
        JsonNode charge = payment(ride[0]);
        assertThat(charge.path("billingType").asString()).isEqualTo("PIX");
        assertThat(charge.path("customer").asString()).startsWith("cus_");
        assertThat(charge.path("value").decimalValue().movePointRight(2).longValueExact()).isEqualTo(total);
        assertThat(charge.path("split").path(0).path("walletId").asString()).isEqualTo("7baf1a2c-1111-4222-8333-944455556666");
        assertThat(charge.path("split").path(0).path("fixedValue").decimalValue().movePointRight(2).longValueExact())
                .isEqualTo(earning);

        JsonNode pay = call(HttpMethod.GET, "/api/v1/rides/" + ride[0] + "/payment", pax, null, 200);
        assertThat(pay.get("pixCopyPaste").asString()).startsWith("000201");
        assertThat(pay.get("pixQrCodeBase64").asString()).isNotBlank();
        assertThat(pay.get("splitToDriver").asBoolean()).isTrue();
        call(HttpMethod.GET, "/api/v1/rides/" + ride[0] + "/payment", passenger(), null, 404);

        String id = charge.path("id").asString();
        String received = "{\"id\":\"evt_rcv_1\",\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"" + id
                + "\",\"value\":" + charge.path("value") + ",\"netValue\":" + (charge.path("value").asDouble() - 0.99)
                + ",\"externalReference\":\"" + ride[0] + "\"}}";
        assertThat(webhook(received, null)).isEqualTo(401);
        assertThat(webhook(received, "token-errado")).isEqualTo(401);
        assertThat(webhook(received, TOKEN)).isEqualTo(200);
        assertThat(webhook(received, TOKEN)).isEqualTo(200); // reentrega: idempotente
        // Evento de cobrança que não é da plataforma: 200 (não trava a fila do Asaas) e nada muda
        assertThat(webhook("{\"id\":\"evt_x\",\"event\":\"PAYMENT_RECEIVED\",\"payment\":{\"id\":\"pay_alheio\",\"value\":5}}",
                TOKEN)).isEqualTo(200);
        assertThat(webhook("{\"id\":\"evt_y\",\"event\":\"PAYMENT_CREATED\",\"payment\":{\"id\":\"" + id + "\"}}", TOKEN))
                .isEqualTo(200);

        JsonNode detail = call(HttpMethod.GET, "/api/v1/admin/rides/" + ride[0], admin, null, 200);
        assertThat(detail.get("ride").get("paymentStatus").asString()).isEqualTo("CONFIRMED");
        JsonNode ledger = detail.get("ledger");
        assertThat(ledger.valueStream().filter(e -> e.get("type").asString().equals("PROVIDER_FEE"))
                .mapToLong(e -> e.get("amountCents").asLong()).sum()).isEqualTo(99);
        assertThat(ledger.valueStream().filter(e -> e.get("type").asString().equals("DRIVER_EARNING"))
                .map(e -> e.get("status").asString()).toList()).containsExactly("PAID");
        // A parte do motorista já foi repassada pelo Asaas: não pode ser sacada de novo pela plataforma
        assertThat(call(HttpMethod.GET, "/api/v1/drivers/me/finance", driver, null, 200).get("withdrawableCents").asLong())
                .isZero();
        JsonNode after = call(HttpMethod.GET, "/api/v1/rides/" + ride[0] + "/payment", pax, null, 200);
        assertThat(after.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(after.get("pixCopyPaste").isNull()).isTrue();

        // ---- 2) Carteira recusada pelo Asaas: cobra sem split; parte do motorista vai ao saldo de saque.
        //         Confirmação pela conciliação ativa (consulta ao Asaas), sem webhook.
        Actor driver2 = approvedOnlineDriver(admin, cityId, LAT, LNG);
        call(HttpMethod.PUT, "/api/v1/drivers/me/finance/split-account", driver2, Map.of("walletId", INVALID_WALLET), 200);
        call(HttpMethod.POST, "/api/v1/drivers/me/offline", driver, null, 204);
        Actor pax2 = passenger();
        long[] v2 = completedPixRide(driver2, pax2, ride);
        JsonNode charge2 = payment(ride[0]);
        assertThat(charge2.has("split")).isFalse();
        assertThat(customersCreated.get()).isEqualTo(2);

        JsonNode pending = call(HttpMethod.POST, "/api/v1/rides/" + ride[0] + "/payment/refresh", pax2, null, 200);
        assertThat(pending.get("status").asString()).isEqualTo("AWAITING_PROVIDER");
        assertThat(pending.get("splitToDriver").asBoolean()).isFalse();
        statusById.put(charge2.path("id").asString(), "RECEIVED");
        JsonNode paid = call(HttpMethod.POST, "/api/v1/rides/" + ride[0] + "/payment/refresh", pax2, null, 200);
        assertThat(paid.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(call(HttpMethod.GET, "/api/v1/drivers/me/finance", driver2, null, 200).get("withdrawableCents").asLong())
                .isEqualTo(v2[0]);
    }
}
