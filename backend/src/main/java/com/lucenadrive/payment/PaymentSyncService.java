package com.lucenadrive.payment;

import com.lucenadrive.payment.webhook.ProviderEvent;
import com.lucenadrive.payment.webhook.WebhookService;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.scheduling.SchedulerLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Conciliação ativa: consulta o provedor (chamada autenticada à API dele) sobre cobranças ainda
 * aguardando confirmação e aplica o resultado pelo mesmo pipeline idempotente dos webhooks.
 * Cobre webhooks perdidos ou ambientes sem endereço público. Também refaz cobranças cuja criação falhou.
 */
@Service
public class PaymentSyncService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSyncService.class);
    private static final Duration WINDOW = Duration.ofDays(3);

    private final DocStore store;
    private final PaymentProvider provider;
    private final WebhookService webhooks;
    private final RideSettlementService settlement;
    private final SchedulerLock lock;
    private final Clock clock;

    public PaymentSyncService(DocStore store, PaymentProvider provider, WebhookService webhooks,
                              RideSettlementService settlement, SchedulerLock lock, Clock clock) {
        this.store = store;
        this.provider = provider;
        this.webhooks = webhooks;
        this.settlement = settlement;
        this.lock = lock;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${lucena.payments.sync-ms:60000}", initialDelayString = "${lucena.payments.sync-ms:60000}")
    public void sync() {
        if (!provider.configured() || !lock.tryAcquire("payment-sync", Duration.ofMinutes(2))) {
            return;
        }
        Instant now = Instant.now(clock);
        for (PaymentTransaction t : store.query(Query.of(Collections.PAYMENT_TRANSACTIONS)
                .eq("status", PaymentStatus.AWAITING_PROVIDER).where("createdAt", Query.Op.GT, now.minus(WINDOW))
                .limit(100), PaymentTransaction.class)) {
            try {
                if (t.providerChargeId() == null) {
                    if (t.createdAt().isBefore(now.minus(Duration.ofMinutes(1)))) {
                        store.get(Collections.RIDES, t.rideId(), Ride.class).ifPresent(settlement::requestCharge);
                    }
                } else {
                    refresh(t);
                }
            } catch (RuntimeException e) {
                log.warn("payment_sync_failed txn={} err={}", t.id(), e.toString());
            }
        }
    }

    /** Passageiro pediu "já paguei": consulta o provedor na hora. */
    public RideSettlementService.ChargeView refreshForPassenger(String rideId, String passengerId) {
        RideSettlementService.ChargeView view = settlement.retryCharge(rideId, passengerId);
        if (view.chargeCreated() && view.status() == PaymentStatus.AWAITING_PROVIDER) {
            store.query(Query.of(Collections.PAYMENT_TRANSACTIONS).eq("rideId", rideId).limit(1), PaymentTransaction.class)
                    .stream().findFirst().ifPresent(t -> {
                        try {
                            refresh(t);
                        } catch (RuntimeException e) {
                            log.warn("payment_refresh_failed ride={} err={}", rideId, e.toString());
                        }
                    });
            return settlement.chargeFor(rideId, passengerId);
        }
        return view;
    }

    private void refresh(PaymentTransaction t) {
        Optional<ProviderEvent> event = provider.fetchChargeEvent(t.providerChargeId());
        event.ifPresent(webhooks::process);
    }
}
