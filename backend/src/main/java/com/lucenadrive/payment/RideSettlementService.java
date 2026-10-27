package com.lucenadrive.payment;

import com.lucenadrive.common.web.Ids;
import com.lucenadrive.ledger.LedgerEntry;
import com.lucenadrive.ledger.LedgerEntryType;
import com.lucenadrive.ledger.LedgerService;
import com.lucenadrive.ledger.LedgerStatus;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.Tx;
import com.lucenadrive.ride.Ride;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Lançamentos financeiros da conclusão/cancelamento de corridas, gravados na mesma transação que
 * muda o estado da corrida. Chaves de idempotência determinísticas impedem duplicidade.
 */
@Service
public class RideSettlementService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RideSettlementService.class);

    private final LedgerService ledger;
    private final PaymentProvider provider;
    private final com.lucenadrive.persistence.DocStore store;
    private final Clock clock;

    public RideSettlementService(LedgerService ledger, PaymentProvider provider,
                                 com.lucenadrive.persistence.DocStore store, Clock clock) {
        this.ledger = ledger;
        this.provider = provider;
        this.store = store;
        this.clock = clock;
    }

    /**
     * Após concluir corrida Pix/cartão: cria a cobrança no provedor. Falhas deixam a transação em
     * AWAITING_PROVIDER sem identificador externo — o relatório de conciliação aponta o caso.
     */
    public void requestCharge(Ride ride) {
        if (ride.paymentMethod() == PaymentMethod.CASH || ride.fare().finalCents() <= 0) {
            return;
        }
        var txns = store.query(com.lucenadrive.persistence.Query.of(Collections.PAYMENT_TRANSACTIONS)
                .eq("rideId", ride.id()).limit(1), PaymentTransaction.class);
        if (txns.isEmpty() || txns.get(0).providerChargeId() != null) {
            return;
        }
        PaymentTransaction t = txns.get(0);
        try {
            PaymentProvider.ChargeResult result = provider.createCharge(new PaymentProvider.ChargeRequest(
                    t.idempotencyKey(), ride.id(), t.amountCents(), t.method(), ride.passengerId(),
                    "Lucena Drive corrida " + ride.code()));
            store.set(Collections.PAYMENT_TRANSACTIONS, t.id(), new PaymentTransaction(t.id(), t.rideId(),
                    t.passengerId(), t.driverId(), t.method(), t.amountCents(), t.currency(), t.status(), provider.name(),
                    result.providerChargeId(), t.providerFeeCents(), t.statusHistory(), t.idempotencyKey(),
                    t.createdAt(), Instant.now(clock)));
        } catch (RuntimeException e) {
            log.warn("charge_creation_failed ride={} err={}", ride.id(), e.toString());
        }
    }

    public record Plan(List<LedgerService.Draft> drafts, PaymentTransaction transaction, PaymentStatus rideStatus) {
    }

    /** Fase de leitura: monta o plano (verifica idempotência). */
    public Plan planCompletion(Tx tx, Ride ride) {
        var f = ride.fare();
        String driverAcc = LedgerEntry.driverAccount(ride.driverId());
        String key = "ride:" + ride.id();
        List<LedgerService.Draft> drafts = new ArrayList<>();
        PaymentStatus status;
        if (ride.paymentMethod() == PaymentMethod.CASH) {
            status = PaymentStatus.CASH_DECLARED;
            if (f.finalCents() > 0) {
                drafts.add(draft(LedgerEntryType.RIDE_FARE_CASH, driverAcc, LedgerEntry.Direction.CREDIT, f.finalCents(),
                        ride, LedgerStatus.RECORDED, key + ":fare_cash", "Valor recebido em dinheiro pelo motorista"));
            }
            if (f.commissionCents() > 0) {
                drafts.add(draft(LedgerEntryType.PLATFORM_COMMISSION, LedgerEntry.PLATFORM, LedgerEntry.Direction.CREDIT,
                        f.commissionCents(), ride, LedgerStatus.AWAITING_SETTLEMENT, key + ":commission",
                        "Comissão a receber do motorista (corrida em dinheiro)"));
                drafts.add(draft(LedgerEntryType.DRIVER_COMMISSION_DUE, driverAcc, LedgerEntry.Direction.DEBIT,
                        f.commissionCents(), ride, LedgerStatus.AWAITING_SETTLEMENT, key + ":commission_due",
                        "Comissão devida à plataforma"));
            }
        } else {
            // Pix/cartão: valores só são lançados como aguardando liquidação; a confirmação
            // vem exclusivamente do webhook autenticado do provedor.
            status = PaymentStatus.AWAITING_PROVIDER;
            if (f.finalCents() > 0) {
                drafts.add(draft(LedgerEntryType.RIDE_PAYMENT, LedgerEntry.PLATFORM, LedgerEntry.Direction.CREDIT,
                        f.finalCents(), ride, LedgerStatus.AWAITING_SETTLEMENT, key + ":payment",
                        "Pagamento da corrida (aguardando confirmação do provedor)"));
            }
            drafts.add(draft(LedgerEntryType.DRIVER_EARNING, driverAcc, LedgerEntry.Direction.CREDIT,
                    f.driverEarningCents(), ride, LedgerStatus.AWAITING_SETTLEMENT, key + ":earning", "Ganho da corrida"));
            if (f.commissionCents() > 0) {
                drafts.add(draft(LedgerEntryType.PLATFORM_COMMISSION, LedgerEntry.PLATFORM, LedgerEntry.Direction.CREDIT,
                        f.commissionCents(), ride, LedgerStatus.AWAITING_SETTLEMENT, key + ":commission", "Comissão"));
            }
        }
        if (f.discountCents() > 0) {
            drafts.add(draft(LedgerEntryType.COUPON_SUBSIDY, LedgerEntry.PLATFORM, LedgerEntry.Direction.DEBIT,
                    f.discountCents(), ride, LedgerStatus.AWAITING_SETTLEMENT, key + ":coupon_platform",
                    "Subsídio de cupom " + ride.couponCode()));
            if (ride.paymentMethod() == PaymentMethod.CASH) {
                drafts.add(draft(LedgerEntryType.COUPON_SUBSIDY, driverAcc, LedgerEntry.Direction.CREDIT,
                        f.discountCents(), ride, LedgerStatus.AWAITING_SETTLEMENT, key + ":coupon_driver",
                        "Desconto de cupom a repassar ao motorista"));
            }
        }
        List<LedgerService.Draft> fresh = drafts.stream().filter(d -> !ledger.exists(tx, d.idempotencyKey())).toList();
        Instant now = Instant.now(clock);
        PaymentTransaction txn = new PaymentTransaction(Ids.newId(), ride.id(), ride.passengerId(), ride.driverId(),
                ride.paymentMethod(), f.finalCents(), "BRL", status, ride.paymentMethod() == PaymentMethod.CASH ? null : "pending",
                null, null, List.of(new PaymentTransaction.StatusChange(status, now, "ride_completed")),
                key + ":payment_txn", now, now);
        return new Plan(fresh, txn, status);
    }

    public void apply(Tx tx, Plan plan) {
        plan.drafts().forEach(d -> ledger.write(tx, d));
        tx.set(Collections.PAYMENT_TRANSACTIONS, plan.transaction().id(), plan.transaction());
    }

    /** Taxa de cancelamento (registrada como devida; cobrança real depende do provedor). */
    public List<LedgerService.Draft> planCancellationFee(Tx tx, Ride ride, long feeCents) {
        if (feeCents <= 0 || ride.driverId() == null) {
            return List.of();
        }
        String key = "ride:" + ride.id() + ":cancel_fee";
        if (ledger.exists(tx, key)) {
            return List.of();
        }
        return List.of(draft(LedgerEntryType.CANCELLATION_FEE, LedgerEntry.driverAccount(ride.driverId()),
                LedgerEntry.Direction.CREDIT, feeCents, ride, LedgerStatus.AWAITING_SETTLEMENT, key,
                "Taxa de cancelamento devida pelo passageiro (cobrança pendente)"));
    }

    public void applyDrafts(Tx tx, List<LedgerService.Draft> drafts) {
        drafts.forEach(d -> ledger.write(tx, d));
    }

    private static LedgerService.Draft draft(LedgerEntryType type, String account, LedgerEntry.Direction dir, long cents,
                                             Ride ride, LedgerStatus status, String key, String description) {
        String userId = account.startsWith("DRIVER:") ? ride.driverId() : null;
        return new LedgerService.Draft(type, account, dir, cents, ride.id(), userId, null, null, status, key, null,
                description);
    }
}
