package com.lucenadrive.payment.webhook;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.config.LucenaProperties;
import com.lucenadrive.ledger.LedgerEntry;
import com.lucenadrive.ledger.LedgerEntryType;
import com.lucenadrive.ledger.LedgerService;
import com.lucenadrive.ledger.LedgerStatus;
import com.lucenadrive.notification.NotificationService;
import com.lucenadrive.notification.NotificationType;
import com.lucenadrive.payment.PaymentStatus;
import com.lucenadrive.payment.PaymentTransaction;
import com.lucenadrive.payout.PayoutRequest;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.ride.Ride;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Processamento de webhooks do provedor: assinatura verificada, registro idempotente por
 * eventId (eventos repetidos são ignorados) e efeitos aplicados em transação.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final DocStore store;
    private final LedgerService ledger;
    private final NotificationService notifications;
    private final LucenaProperties props;
    private final JsonMapper json;
    private final Clock clock;

    public WebhookService(DocStore store, LedgerService ledger, NotificationService notifications,
                          LucenaProperties props, JsonMapper json, Clock clock) {
        this.store = store;
        this.ledger = ledger;
        this.notifications = notifications;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    public enum Outcome { PROCESSED, DUPLICATE }

    public Outcome receive(String provider, byte[] body, String signature) {
        String secret = props.payments().webhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED, "Webhooks de pagamento não configurados.");
        }
        if (!provider.equalsIgnoreCase(props.payments().provider())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Provedor desconhecido.");
        }
        if (!WebhookSignature.verify(secret, body, signature)) {
            log.warn("webhook_invalid_signature provider={}", provider);
            throw new ApiException(ErrorCode.INVALID_SIGNATURE, "Assinatura inválida.");
        }
        ProviderEvent event = parse(provider, body);
        return process(event);
    }

    /**
     * Formato genérico (usado em homologação e pelos testes):
     * {"id":"evt_1","type":"PAYMENT_CONFIRMED","data":{"chargeId":"...","transferId":"...",
     *  "amountCents":1234,"feeCents":12,"receiptUrl":"...","failureReason":"..."}}
     * Adaptadores de provedores reais traduzem o payload próprio para {@link ProviderEvent}.
     */
    ProviderEvent parse(String provider, byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode data = root.path("data");
            return new ProviderEvent(provider, root.path("id").asString(), ProviderEvent.Type.valueOf(root.path("type").asString()),
                    text(data, "chargeId"), text(data, "transferId"),
                    data.has("amountCents") ? data.get("amountCents").asLong() : null,
                    data.has("feeCents") ? data.get("feeCents").asLong() : null,
                    text(data, "receiptUrl"), text(data, "failureReason"), Instant.now(clock));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Payload de webhook inválido.");
        }
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }

    public Outcome process(ProviderEvent e) {
        if (e.eventId() == null || e.eventId().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Evento sem identificador.");
        }
        String recordId = e.provider() + "_" + e.eventId();
        Instant now = Instant.now(clock);
        boolean first = store.createIfAbsent(Collections.WEBHOOK_EVENTS, recordId,
                new WebhookEventRecord(recordId, e.provider(), e.eventId(), e.type(), "RECEIVED", null, now, null));
        if (!first) {
            WebhookEventRecord existing = store.get(Collections.WEBHOOK_EVENTS, recordId, WebhookEventRecord.class).orElseThrow();
            if ("PROCESSED".equals(existing.status())) {
                return Outcome.DUPLICATE;
            }
        }
        try {
            switch (e.type()) {
                case PAYMENT_CONFIRMED, PAYMENT_SETTLED, PAYMENT_FAILED, PAYMENT_REFUNDED, CHARGEBACK -> handlePayment(e);
                case TRANSFER_COMPLETED, TRANSFER_FAILED -> handleTransfer(e);
            }
            store.set(Collections.WEBHOOK_EVENTS, recordId,
                    new WebhookEventRecord(recordId, e.provider(), e.eventId(), e.type(), "PROCESSED", null, now, Instant.now(clock)));
            return Outcome.PROCESSED;
        } catch (RuntimeException ex) {
            store.set(Collections.WEBHOOK_EVENTS, recordId,
                    new WebhookEventRecord(recordId, e.provider(), e.eventId(), e.type(), "FAILED", ex.getMessage(), now, null));
            throw ex;
        }
    }

    private void handlePayment(ProviderEvent e) {
        List<PaymentTransaction> txns = store.query(Query.of(Collections.PAYMENT_TRANSACTIONS)
                .eq("providerChargeId", e.chargeId()).limit(1), PaymentTransaction.class);
        if (txns.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Cobrança desconhecida: " + e.chargeId());
        }
        String txnId = txns.get(0).id();
        PaymentTransaction updated = store.transaction(tx -> {
            PaymentTransaction t = tx.get(Collections.PAYMENT_TRANSACTIONS, txnId, PaymentTransaction.class).orElseThrow();
            Ride ride = tx.get(Collections.RIDES, t.rideId(), Ride.class).orElseThrow();
            List<LedgerEntry> entries = ledger.byRideInTx(tx, t.rideId());
            String feeKey = "provider_fee:" + e.provider() + ":" + e.chargeId();
            String refundKey = "refund:" + e.provider() + ":" + e.eventId();
            String chargebackKey = "chargeback:" + e.provider() + ":" + e.eventId();
            boolean feeExists = ledger.exists(tx, feeKey);
            boolean refundExists = ledger.exists(tx, refundKey);
            boolean chargebackExists = ledger.exists(tx, chargebackKey);
            Instant now = Instant.now(clock);
            PaymentTransaction next = t;
            switch (e.type()) {
                case PAYMENT_CONFIRMED -> {
                    next = t.withStatus(PaymentStatus.CONFIRMED, now, "webhook:" + e.eventId(), e.feeCents());
                    if (e.feeCents() != null && e.feeCents() > 0 && !feeExists) {
                        ledger.write(tx, new LedgerService.Draft(LedgerEntryType.PROVIDER_FEE, LedgerEntry.PLATFORM,
                                LedgerEntry.Direction.DEBIT, e.feeCents(), t.rideId(), null, e.provider(), e.chargeId(),
                                LedgerStatus.AWAITING_SETTLEMENT, feeKey, null, "Tarifa do provedor"));
                    }
                }
                case PAYMENT_SETTLED -> {
                    for (LedgerEntry le : entries) {
                        if (le.status() == LedgerStatus.AWAITING_SETTLEMENT && le.type() != LedgerEntryType.DRIVER_COMMISSION_DUE) {
                            ledger.transition(tx, le, LedgerStatus.AVAILABLE, "settled:" + e.eventId(), e.chargeId());
                        }
                    }
                }
                case PAYMENT_FAILED -> next = t.withStatus(PaymentStatus.FAILED, now, "webhook:" + e.eventId(), null);
                case PAYMENT_REFUNDED -> {
                    next = t.withStatus(PaymentStatus.REFUNDED, now, "webhook:" + e.eventId(), null);
                    long amount = e.amountCents() != null ? e.amountCents() : t.amountCents();
                    if (!refundExists) {
                        ledger.write(tx, new LedgerService.Draft(LedgerEntryType.REFUND, LedgerEntry.PLATFORM,
                                LedgerEntry.Direction.DEBIT, amount, t.rideId(), t.passengerId(), e.provider(), e.eventId(),
                                LedgerStatus.PAID, refundKey, null, "Reembolso ao passageiro"));
                    }
                }
                case CHARGEBACK -> {
                    next = t.withStatus(PaymentStatus.CHARGEBACK, now, "webhook:" + e.eventId(), null);
                    long amount = e.amountCents() != null ? e.amountCents() : t.amountCents();
                    if (!chargebackExists) {
                        ledger.write(tx, new LedgerService.Draft(LedgerEntryType.CHARGEBACK, LedgerEntry.PLATFORM,
                                LedgerEntry.Direction.DEBIT, amount, t.rideId(), t.passengerId(), e.provider(), e.eventId(),
                                LedgerStatus.PAID, chargebackKey, null, "Chargeback"));
                    }
                }
                default -> {
                }
            }
            tx.set(Collections.PAYMENT_TRANSACTIONS, t.id(), next);
            if (next.status() != t.status()) {
                tx.set(Collections.RIDES, ride.id(), ride.toBuilder().paymentStatus(next.status()).updatedAt(now).build());
            }
            return next;
        });
        switch (e.type()) {
            case PAYMENT_CONFIRMED -> notifications.notify(updated.passengerId(), NotificationType.PAYMENT_CONFIRMED,
                    "Pagamento confirmado", "O pagamento da sua corrida foi confirmado.", Map.of("rideId", updated.rideId()));
            case PAYMENT_FAILED -> notifications.notify(updated.passengerId(), NotificationType.FINANCIAL_FAILURE,
                    "Falha no pagamento", "Não foi possível confirmar o pagamento da corrida.", Map.of("rideId", updated.rideId()));
            case PAYMENT_REFUNDED -> notifications.notify(updated.passengerId(), NotificationType.REFUND,
                    "Reembolso processado", "Um reembolso foi processado para sua corrida.", Map.of("rideId", updated.rideId()));
            default -> {
            }
        }
    }

    private void handleTransfer(ProviderEvent e) {
        List<PayoutRequest> found = store.query(Query.of(Collections.PAYOUT_REQUESTS)
                .eq("providerTransferId", e.transferId()).limit(1), PayoutRequest.class);
        if (found.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Transferência desconhecida: " + e.transferId());
        }
        String payoutId = found.get(0).id();
        PayoutRequest updated = store.transaction(tx -> {
            PayoutRequest p = tx.get(Collections.PAYOUT_REQUESTS, payoutId, PayoutRequest.class).orElseThrow();
            if (p.finished()) {
                return p;
            }
            String key = "payout:" + p.id();
            LedgerEntry entry = tx.get(Collections.LEDGER_ENTRIES, LedgerService.idFor(key), LedgerEntry.class).orElse(null);
            Instant now = Instant.now(clock);
            PayoutRequest next;
            if (e.type() == ProviderEvent.Type.TRANSFER_COMPLETED) {
                next = p.with(PayoutRequest.Status.COMPLETED, "provider", "webhook:" + e.eventId(), now, null, e.receiptUrl(), null);
                if (entry != null) {
                    ledger.transition(tx, entry, LedgerStatus.PAID, "transfer_completed", e.transferId());
                }
            } else {
                next = p.with(PayoutRequest.Status.FAILED, "provider", "webhook:" + e.eventId(), now, null, null,
                        e.failureReason() != null ? e.failureReason() : "Falha informada pelo provedor");
                if (entry != null) {
                    ledger.transition(tx, entry, LedgerStatus.REVERSED, "transfer_failed", e.transferId());
                }
            }
            tx.set(Collections.PAYOUT_REQUESTS, p.id(), next);
            return next;
        });
        String target = updated.ownerType() == PayoutRequest.OwnerType.DRIVER ? updated.ownerId() : updated.requestedBy();
        if (updated.status() == PayoutRequest.Status.COMPLETED) {
            notifications.notify(target, NotificationType.PAYOUT, "Saque concluído", "Seu saque via Pix foi concluído.",
                    Map.of("payoutId", updated.id()));
        } else if (updated.status() == PayoutRequest.Status.FAILED) {
            notifications.notify(target, NotificationType.FINANCIAL_FAILURE, "Saque não concluído",
                    "O provedor informou falha no saque.", Map.of("payoutId", updated.id()));
        }
    }

}
