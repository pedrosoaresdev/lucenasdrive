package com.lucenadrive.finance;

import com.lucenadrive.ledger.LedgerEntry;
import com.lucenadrive.ledger.LedgerEntryType;
import com.lucenadrive.ledger.LedgerService;
import com.lucenadrive.ledger.LedgerStatus;
import com.lucenadrive.payment.PaymentProvider;
import com.lucenadrive.payment.PaymentStatus;
import com.lucenadrive.payment.PaymentTransaction;
import com.lucenadrive.payout.PayoutRequest;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.ride.RideStatus;
import com.lucenadrive.user.IdentityRegistrationService;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Relatórios financeiros a partir do ledger. Valores calculados (registrados) e valores
 * liquidados são sempre apresentados separadamente; o saldo para saque da plataforma vem do provedor.
 */
@Service
public class FinanceService {

    public record PeriodEarnings(String period, long rides, long grossCents, long commissionCents, long earningsCents) {
    }

    public record DriverFinance(List<PeriodEarnings> periods, Map<LedgerStatus, Long> balanceByStatus,
                                long withdrawableCents, List<LedgerEntry> recentEntries, List<PayoutRequest> payouts,
                                boolean payoutsAvailable) {
    }

    public record PlatformFinance(
            Instant from, Instant to,
            long grossRideValueCents,
            long cashRideValueCents,
            long digitalPaymentsConfirmedCents,
            long commissionCalculatedCents,
            long commissionReceivedCents,
            long awaitingSettlementCents,
            long couponSubsidyCents,
            long providerFeesCents,
            long refundsCents,
            long chargebacksCents,
            long driverPayoutsPendingCents,
            long driverPayoutsCompletedCents,
            Long providerAvailableBalanceCents,
            boolean providerConfigured) {
    }

    public record ReconciliationIssue(String kind, String referenceId, String description, Instant since) {
    }

    private final DocStore store;
    private final LedgerService ledger;
    private final PaymentProvider provider;
    private final Clock clock;

    public FinanceService(DocStore store, LedgerService ledger, PaymentProvider provider, Clock clock) {
        this.store = store;
        this.ledger = ledger;
        this.provider = provider;
        this.clock = clock;
    }

    public DriverFinance driver(String driverId) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(IdentityRegistrationService.BR));
        LocalDate today = now.toLocalDate();
        Instant dayStart = today.atStartOfDay(now.getZone()).toInstant();
        Instant weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(now.getZone()).toInstant();
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(now.getZone()).toInstant();
        List<Ride> monthRides = store.query(Query.of(Collections.RIDES).eq("driverId", driverId)
                .eq("status", RideStatus.COMPLETED).where("timeline.completedAt", Query.Op.GTE, monthStart)
                .limit(2000), Ride.class);
        List<PeriodEarnings> periods = List.of(
                summarize("DAY", monthRides, dayStart),
                summarize("WEEK", monthRides, weekStart),
                summarize("MONTH", monthRides, monthStart));
        String account = LedgerEntry.driverAccount(driverId);
        Map<LedgerStatus, Long> byStatus = new EnumMap<>(ledger.balance(account).byStatus());
        return new DriverFinance(periods, byStatus, Math.max(0, ledger.withdrawable(account)),
                ledger.byAccount(account, null, null, 50),
                store.query(Query.of(Collections.PAYOUT_REQUESTS).eq("ownerId", driverId)
                        .orderBy("createdAt", Query.Direction.DESC).limit(20), PayoutRequest.class),
                provider.configured());
    }

    private static PeriodEarnings summarize(String label, List<Ride> rides, Instant from) {
        List<Ride> in = rides.stream().filter(r -> !r.timeline().completedAt().isBefore(from)).toList();
        return new PeriodEarnings(label, in.size(),
                in.stream().mapToLong(r -> r.fare().totalCents()).sum(),
                in.stream().mapToLong(r -> r.fare().commissionCents()).sum(),
                in.stream().mapToLong(r -> r.fare().driverEarningCents()).sum());
    }

    public PlatformFinance platform(Instant from, Instant to) {
        Query completed = Query.of(Collections.RIDES).eq("status", RideStatus.COMPLETED)
                .where("timeline.completedAt", Query.Op.GTE, from).where("timeline.completedAt", Query.Op.LT, to);
        long gross = store.sum(completed, "fare.totalCents");
        List<LedgerEntry> entries = store.query(Query.of(Collections.LEDGER_ENTRIES)
                .where("createdAt", Query.Op.GTE, from).where("createdAt", Query.Op.LT, to).limit(20_000), LedgerEntry.class);
        long cash = 0, commission = 0, commissionReceived = 0, awaiting = 0, coupon = 0, fees = 0, refunds = 0,
                chargebacks = 0, payoutsPending = 0, payoutsDone = 0;
        for (LedgerEntry e : entries) {
            boolean reversed = e.status() == LedgerStatus.REVERSED;
            switch (e.type()) {
                case RIDE_FARE_CASH -> cash += e.amountCents();
                case PLATFORM_COMMISSION -> {
                    if (!reversed) {
                        commission += e.amountCents();
                        if (e.status() == LedgerStatus.AVAILABLE || e.status() == LedgerStatus.PAID) {
                            commissionReceived += e.amountCents();
                        }
                    }
                }
                case COUPON_SUBSIDY -> {
                    if (LedgerEntry.PLATFORM.equals(e.account()) && !reversed) {
                        coupon += e.amountCents();
                    }
                }
                case PROVIDER_FEE -> fees += reversed ? 0 : e.amountCents();
                case REFUND -> refunds += reversed ? 0 : e.amountCents();
                case CHARGEBACK -> chargebacks += reversed ? 0 : e.amountCents();
                case PAYOUT -> {
                    if (e.account().startsWith("DRIVER:")) {
                        if (e.status() == LedgerStatus.PAID) {
                            payoutsDone += e.amountCents();
                        } else if (!reversed) {
                            payoutsPending += e.amountCents();
                        }
                    }
                }
                default -> {
                }
            }
            if (LedgerEntry.PLATFORM.equals(e.account()) && e.status() == LedgerStatus.AWAITING_SETTLEMENT) {
                awaiting += e.signedAmount();
            }
        }
        long confirmed = store.query(Query.of(Collections.PAYMENT_TRANSACTIONS).eq("status", PaymentStatus.CONFIRMED)
                        .where("updatedAt", Query.Op.GTE, from).where("updatedAt", Query.Op.LT, to).limit(20_000),
                        PaymentTransaction.class)
                .stream().mapToLong(PaymentTransaction::amountCents).sum();
        Long providerBalance = provider.configured() ? provider.availableBalanceCents().orElse(null) : null;
        return new PlatformFinance(from, to, gross, cash, confirmed, commission, commissionReceived, awaiting, coupon,
                fees, refunds, chargebacks, payoutsPending, payoutsDone, providerBalance, provider.configured());
    }

    /**
     * Conciliação interna: aponta pendências que exigem confronto com o extrato do provedor.
     * A comparação automática com o extrato depende da API do provedor contratado.
     */
    public List<ReconciliationIssue> reconciliation() {
        Instant cutoff = Instant.now(clock).minus(Duration.ofHours(24));
        List<ReconciliationIssue> issues = new ArrayList<>();
        store.query(Query.of(Collections.PAYMENT_TRANSACTIONS).eq("status", PaymentStatus.AWAITING_PROVIDER)
                        .where("createdAt", Query.Op.LT, cutoff).limit(200), PaymentTransaction.class)
                .forEach(t -> issues.add(new ReconciliationIssue("PAYMENT_NOT_CONFIRMED", t.id(),
                        t.providerChargeId() == null ? "Cobrança não criada no provedor" : "Sem confirmação há mais de 24h",
                        t.createdAt())));
        store.query(Query.of(Collections.PAYOUT_REQUESTS).eq("status", PayoutRequest.Status.PROCESSING)
                        .where("updatedAt", Query.Op.LT, cutoff).limit(200), PayoutRequest.class)
                .forEach(p -> issues.add(new ReconciliationIssue("PAYOUT_STUCK", p.id(),
                        "Saque em processamento há mais de 24h", p.updatedAt())));
        store.query(Query.of(Collections.WEBHOOK_EVENTS).eq("status", "FAILED").limit(200),
                        com.lucenadrive.payment.webhook.WebhookEventRecord.class)
                .forEach(w -> issues.add(new ReconciliationIssue("WEBHOOK_FAILED", w.id(),
                        "Falha ao processar webhook: " + w.error(), w.receivedAt())));
        return issues;
    }

    public List<LedgerEntry> statement(String account, Instant from, Instant to, int limit) {
        return ledger.byAccount(account, from, to, Math.min(limit, 1000));
    }

    static boolean isCommission(LedgerEntryType t) {
        return t == LedgerEntryType.PLATFORM_COMMISSION;
    }
}
