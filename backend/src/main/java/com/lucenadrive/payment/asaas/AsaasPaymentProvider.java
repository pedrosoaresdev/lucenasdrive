package com.lucenadrive.payment.asaas;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.payment.PaymentMethod;
import com.lucenadrive.payment.PaymentProvider;
import com.lucenadrive.payment.ProviderCustomer;
import com.lucenadrive.payment.webhook.ProviderEvent;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador do Asaas (API v3): cobrança Pix/cartão com split automático para a carteira do
 * motorista, transferências Pix, saldo, webhooks (token no cabeçalho asaas-access-token) e consulta
 * de cobrança para conciliação. Cartão é pago na página do próprio Asaas (invoiceUrl): dados de
 * cartão nunca passam pela plataforma. A chave de API vem só do ambiente do servidor.
 */
public class AsaasPaymentProvider implements PaymentProvider {

    public static final String SANDBOX_URL = "https://api-sandbox.asaas.com/v3";
    public static final String PRODUCTION_URL = "https://api.asaas.com/v3";
    private static final ZoneId BR = ZoneId.of("America/Sao_Paulo");
    private static final Logger log = LoggerFactory.getLogger(AsaasPaymentProvider.class);

    private final RestClient client;
    private final DocStore store;
    private final JsonMapper json;
    private final Clock clock;

    public AsaasPaymentProvider(String baseUrl, String apiKey, DocStore store, JsonMapper json, Clock clock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.client = RestClient.builder().requestFactory(factory).baseUrl(baseUrl.replaceAll("/+$", ""))
                .defaultHeader("access_token", apiKey)
                .defaultHeader("User-Agent", "LucenaDrive/0.1")
                .build();
        this.store = store;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "asaas";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public boolean supportsSplit() {
        return true;
    }

    // ------------------------------------------------------------------ cobrança

    @Override
    public ChargeResult createCharge(ChargeRequest req) {
        if (req.method() == PaymentMethod.CASH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Dinheiro não gera cobrança no provedor.");
        }
        JsonNode payment = existingCharge(req.rideId()).orElse(null);
        if (payment == null) {
            String customer = ensureCustomer(req.payerUserId(), req.payerName(), req.payerCpf());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("customer", customer);
            body.put("billingType", req.method() == PaymentMethod.PIX ? "PIX" : "CREDIT_CARD");
            body.put("value", reais(req.amountCents()));
            body.put("dueDate", LocalDate.now(clock.withZone(BR)).toString());
            body.put("description", req.description());
            body.put("externalReference", req.rideId());
            if (req.splitWalletId() != null && req.splitCents() != null && req.splitCents() > 0) {
                body.put("split", List.of(Map.of("walletId", req.splitWalletId(), "fixedValue", reais(req.splitCents()))));
            }
            try {
                payment = post("/payments", body);
            } catch (ApiException e) {
                if (!body.containsKey("split") || !(e.getCause() instanceof RestClientResponseException r)
                        || r.getStatusCode().value() != 400) {
                    throw e;
                }
                // Carteira inválida/recusada: cobra sem split; a parte do motorista segue pelo saldo e saque Pix.
                log.warn("asaas_split_rejected ride={} err={}", req.rideId(), e.getMessage());
                body.remove("split");
                payment = post("/payments", body);
            }
        }
        String id = payment.path("id").asString();
        boolean split = payment.path("split").isArray() && !payment.path("split").isEmpty();
        String copyPaste = null;
        String qr = null;
        if (req.method() == PaymentMethod.PIX) {
            try {
                JsonNode pix = get("/payments/" + enc(id) + "/pixQrCode");
                copyPaste = text(pix, "payload");
                qr = text(pix, "encodedImage");
            } catch (ApiException e) {
                // Cobrança criada; o QR pode ser obtido depois pela página do Asaas (invoiceUrl).
                log.warn("asaas_pix_qr_failed charge={} err={}", id, e.getMessage());
            }
        }
        return new ChargeResult(id, copyPaste, qr, text(payment, "invoiceUrl"), split);
    }

    /** Asaas não tem chave de idempotência: reaproveita cobrança já criada para a corrida (retentativa após timeout). */
    private Optional<JsonNode> existingCharge(String rideId) {
        JsonNode list = get("/payments?externalReference=" + enc(rideId) + "&limit=10");
        for (JsonNode p : list.path("data")) {
            if (!p.path("deleted").asBoolean(false) && !"REFUNDED".equals(p.path("status").asString())) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private String ensureCustomer(String userId, String name, String cpf) {
        String docId = name() + "_" + userId;
        Optional<ProviderCustomer> cached = store.get(Collections.PROVIDER_CUSTOMERS, docId, ProviderCustomer.class);
        if (cached.isPresent()) {
            return cached.get().customerId();
        }
        String customerId = null;
        for (JsonNode c : get("/customers?externalReference=" + enc(userId) + "&limit=1").path("data")) {
            customerId = c.path("id").asString();
        }
        if (customerId == null) {
            if (cpf == null || cpf.isBlank() || name == null || name.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cadastro do passageiro incompleto para cobrança (nome/CPF).");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            body.put("cpfCnpj", cpf);
            body.put("externalReference", userId);
            body.put("notificationDisabled", true);
            customerId = post("/customers", body).path("id").asString();
        }
        store.set(Collections.PROVIDER_CUSTOMERS, docId,
                new ProviderCustomer(docId, name(), userId, customerId, Instant.now(clock)));
        return customerId;
    }

    @Override
    public Optional<ProviderEvent> fetchChargeEvent(String providerChargeId) {
        JsonNode p = get("/payments/" + enc(providerChargeId));
        String status = p.path("status").asString();
        ProviderEvent.Type type = switch (status) {
            case "CONFIRMED" -> ProviderEvent.Type.PAYMENT_CONFIRMED;
            case "RECEIVED" -> ProviderEvent.Type.PAYMENT_SETTLED;
            case "REFUNDED" -> ProviderEvent.Type.PAYMENT_REFUNDED;
            case "CHARGEBACK_REQUESTED", "CHARGEBACK_DISPUTE" -> ProviderEvent.Type.CHARGEBACK;
            default -> null; // PENDING, OVERDUE (Pix ainda pode ser pago), AWAITING_RISK_ANALYSIS…
        };
        if (type == null) {
            return Optional.empty();
        }
        return Optional.of(paymentEvent("sync_" + providerChargeId + "_" + status, type, p));
    }

    // ------------------------------------------------------------------ transferência e saldo

    @Override
    public TransferResult createTransfer(TransferRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", reais(req.amountCents()));
        body.put("pixAddressKey", req.pixKey());
        body.put("pixAddressKeyType", req.pixKeyType());
        body.put("description", req.description());
        body.put("externalReference", req.idempotencyKey());
        JsonNode t = post("/transfers", body);
        return new TransferResult(t.path("id").asString(), t.path("status").asString());
    }

    @Override
    public Optional<Long> availableBalanceCents() {
        try {
            return Optional.of(cents(get("/finance/balance").path("balance")));
        } catch (RuntimeException e) {
            log.warn("asaas_balance_failed err={}", e.toString());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ webhooks

    @Override
    public Optional<WebhookAdapter> webhookAdapter() {
        return Optional.of(new WebhookAdapter() {
            @Override
            public boolean authentic(byte[] body, Map<String, String> headers, String secret) {
                String token = headers.get("asaas-access-token");
                return token != null && secret != null && !secret.isBlank() && MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public Optional<ProviderEvent> parse(byte[] body) {
                return parseWebhook(json.readTree(body));
            }
        });
    }

    Optional<ProviderEvent> parseWebhook(JsonNode root) {
        String event = root.path("event").asString();
        if (event.startsWith("TRANSFER_")) {
            ProviderEvent.Type type = switch (event) {
                case "TRANSFER_DONE" -> ProviderEvent.Type.TRANSFER_COMPLETED;
                case "TRANSFER_FAILED", "TRANSFER_CANCELLED" -> ProviderEvent.Type.TRANSFER_FAILED;
                default -> null;
            };
            if (type == null) {
                return Optional.empty();
            }
            JsonNode t = root.path("transfer");
            String transferId = t.path("id").asString();
            return Optional.of(new ProviderEvent(name(), eventId(root, event, transferId), type, null, transferId,
                    t.has("value") ? cents(t.get("value")) : null, null, text(t, "transactionReceiptUrl"),
                    text(t, "failReason"), Instant.now(clock)));
        }
        ProviderEvent.Type type = switch (event) {
            case "PAYMENT_CONFIRMED" -> ProviderEvent.Type.PAYMENT_CONFIRMED;
            case "PAYMENT_RECEIVED" -> ProviderEvent.Type.PAYMENT_SETTLED;
            case "PAYMENT_OVERDUE", "PAYMENT_DELETED", "PAYMENT_CREDIT_CARD_CAPTURE_REFUSED",
                 "PAYMENT_REPROVED_BY_RISK_ANALYSIS" -> ProviderEvent.Type.PAYMENT_FAILED;
            case "PAYMENT_REFUNDED" -> ProviderEvent.Type.PAYMENT_REFUNDED;
            case "PAYMENT_CHARGEBACK_REQUESTED" -> ProviderEvent.Type.CHARGEBACK;
            default -> null;
        };
        if (type == null || !root.has("payment")) {
            return Optional.empty();
        }
        JsonNode p = root.path("payment");
        return Optional.of(paymentEvent(eventId(root, event, p.path("id").asString()), type, p));
    }

    private ProviderEvent paymentEvent(String eventId, ProviderEvent.Type type, JsonNode p) {
        Long amount = p.has("value") ? cents(p.get("value")) : null;
        Long fee = null;
        if (p.has("value") && p.has("netValue") && (type == ProviderEvent.Type.PAYMENT_CONFIRMED
                || type == ProviderEvent.Type.PAYMENT_SETTLED)) {
            fee = Math.max(0, cents(p.get("value")) - cents(p.get("netValue")));
        }
        return new ProviderEvent(name(), eventId, type, p.path("id").asString(), null, amount, fee,
                text(p, "transactionReceiptUrl"), null, Instant.now(clock));
    }

    /** Payloads antigos podem vir sem id do evento: usa evento + objeto (cada transição é única por objeto). */
    private static String eventId(JsonNode root, String event, String objectId) {
        String id = text(root, "id");
        return id != null ? id : event + "_" + objectId;
    }

    // ------------------------------------------------------------------ HTTP

    private JsonNode get(String path) {
        return call(() -> client.get().uri(path).retrieve().body(String.class), path);
    }

    private JsonNode post(String path, Map<String, Object> body) {
        return call(() -> client.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body)).retrieve().body(String.class), path);
    }

    private JsonNode call(java.util.function.Supplier<String> request, String path) {
        String resource = path.replaceAll("\\?.*$", "");
        try {
            String raw = request.get();
            return json.readTree(raw == null ? "{}" : raw);
        } catch (RestClientResponseException e) {
            String detail = describe(e.getResponseBodyAsString());
            log.warn("asaas_error status={} resource={} detail={}", e.getStatusCode().value(), resource, detail);
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE,
                    "Asaas recusou a operação" + (detail.isBlank() ? "." : ": " + detail), e);
        } catch (RestClientException e) {
            log.warn("asaas_unreachable resource={} err={}", resource, e.toString());
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Asaas indisponível no momento.", e);
        }
    }

    private String describe(String body) {
        try {
            StringBuilder sb = new StringBuilder();
            for (JsonNode err : json.readTree(body).path("errors")) {
                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append(err.path("description").asString());
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static BigDecimal reais(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    static long cents(JsonNode value) {
        return value.decimalValue().setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) && !n.get(field).asString().isBlank() ? n.get(field).asString() : null;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
