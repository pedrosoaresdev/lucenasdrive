package com.lucenadrive.admin;

import com.lucenadrive.coupon.Coupon;
import com.lucenadrive.driver.DriverStatus;
import com.lucenadrive.finance.FinanceService;
import com.lucenadrive.geo.City;
import com.lucenadrive.payout.PayoutRequest;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.rating.RatingService;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.ride.RideStatus;
import com.lucenadrive.security.Role;
import com.lucenadrive.support.SupportTicket;
import com.lucenadrive.user.IdentityRegistrationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/** Indicadores do painel calculados a partir do banco (contagens/somas agregadas). Nada é estimado. */
@Service
public class DashboardService {

    public record Summary(long totalPassengers, long totalDrivers, long approvedDrivers, long pendingDrivers,
                          long onlineDrivers, long ridesSearching, long ridesInProgress, long ridesCompleted,
                          long ridesCancelled, long activeCoupons, double platformRatingAverage, long platformRatingCount,
                          long pendingPayouts, long openSafetyTickets, long reconciliationIssues,
                          FinanceService.PlatformFinance finance, List<CityCount> ridesByCity, Instant generatedAt,
                          List<String> unavailableMetrics) {
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DashboardService.class);

    /**
     * Calcula um indicador isoladamente: se falhar (ex.: índice do Firestore ainda em construção), registra o motivo
     * no log e devolve o valor padrão, sem derrubar o painel inteiro.
     */
    private <T> T metric(String name, java.util.function.Supplier<T> fn, T fallback, List<String> failed) {
        try {
            return fn.get();
        } catch (RuntimeException e) {
            failed.add(name);
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            log.error("dashboard_metric_failed metric={} cause={}", name, root.getMessage(), e);
            return fallback;
        }
    }

    public record CityCount(String cityId, String cityName, long completedRides, long activeRides) {
    }

    public record DayPoint(String date, long completedRides, long cancelledRides, long grossCents, long commissionCents) {
    }

    private final DocStore store;
    private final FinanceService finance;
    private final RatingService ratings;
    private final Clock clock;

    public DashboardService(DocStore store, FinanceService finance, RatingService ratings, Clock clock) {
        this.store = store;
        this.finance = finance;
        this.ratings = ratings;
        this.clock = clock;
    }

    public Summary summary() {
        Instant now = Instant.now(clock);
        List<String> f = new ArrayList<>();
        long passengers = metric("Passageiros", () -> store.count(Query.of(Collections.USERS)
                .where("roles", Query.Op.ARRAY_CONTAINS, Role.PASSENGER)), 0L, f);
        long drivers = metric("Motoristas", () -> store.count(Query.of(Collections.DRIVER_PROFILES)), 0L, f);
        long approved = metric("Motoristas aprovados", () -> store.count(Query.of(Collections.DRIVER_PROFILES)
                .eq("status", DriverStatus.APPROVED)), 0L, f);
        long pending = metric("Motoristas em análise", () -> store.count(Query.of(Collections.DRIVER_PROFILES)
                .eq("status", DriverStatus.PENDING_REVIEW)), 0L, f);
        long online = metric("Motoristas online", () -> store.count(Query.of(Collections.DRIVER_LOCATIONS)
                .eq("online", true)), 0L, f);
        long searching = metric("Corridas procurando motorista", () -> store.count(Query.of(Collections.RIDES)
                .eq("status", RideStatus.SEARCHING_DRIVER)), 0L, f);
        long inProgress = metric("Corridas em andamento", () -> store.count(Query.of(Collections.RIDES)
                .where("status", Query.Op.IN, RideStatus.WITH_DRIVER)), 0L, f);
        long completed = metric("Corridas concluídas", () -> store.count(Query.of(Collections.RIDES)
                .eq("status", RideStatus.COMPLETED)), 0L, f);
        long cancelled = metric("Corridas canceladas", () -> store.count(Query.of(Collections.RIDES)
                .eq("status", RideStatus.CANCELLED)), 0L, f);
        long coupons = metric("Cupons ativos", () -> store.count(Query.of(Collections.COUPONS).eq("active", true)), 0L, f);
        Map<String, Object> rs = metric("Avaliações", ratings::platformStats, Map.<String, Object>of("average", 0.0, "count", 0L), f);
        long pendingPayouts = metric("Saques pendentes", () -> store.count(Query.of(Collections.PAYOUT_REQUESTS)
                .where("status", Query.Op.IN, List.of(PayoutRequest.Status.REQUESTED, PayoutRequest.Status.IN_VALIDATION,
                        PayoutRequest.Status.PROCESSING))), 0L, f);
        long safety = metric("Ocorrências de segurança", () -> store.count(Query.of(Collections.SUPPORT_TICKETS)
                .eq("category", SupportTicket.Category.SAFETY).where("status", Query.Op.IN, List.of(SupportTicket.Status.OPEN,
                        SupportTicket.Status.IN_PROGRESS, SupportTicket.Status.ESCALATED))), 0L, f);
        long issues = metric("Conciliação", () -> (long) finance.reconciliation().size(), 0L, f);
        FinanceService.PlatformFinance fin = metric("Financeiro", () -> finance.platform(Instant.EPOCH, now.plusSeconds(1)),
                new FinanceService.PlatformFinance(Instant.EPOCH, now, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, false), f);
        List<CityCount> byCity = metric("Corridas por cidade", () -> {
            List<CityCount> list = new ArrayList<>();
            for (City c : store.query(Query.of(Collections.CITIES), City.class)) {
                list.add(new CityCount(c.id(), c.name(),
                        store.count(Query.of(Collections.RIDES).eq("cityId", c.id()).eq("status", RideStatus.COMPLETED)),
                        store.count(Query.of(Collections.RIDES).eq("cityId", c.id()).where("status", Query.Op.IN, RideStatus.ACTIVE))));
            }
            return list;
        }, List.of(), f);
        return new Summary(passengers, drivers, approved, pending, online, searching, inProgress, completed, cancelled,
                coupons, ((Number) rs.get("average")).doubleValue(), ((Number) rs.get("count")).longValue(),
                pendingPayouts, safety, issues, fin, byCity, now, List.copyOf(f));
    }

    /** Série diária dos últimos N dias (fuso de Brasília). */
    public List<DayPoint> timeseries(int days) {
        int span = Math.min(Math.max(days, 1), 90);
        ZoneId zone = IdentityRegistrationService.BR;
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant from = today.minusDays(span - 1L).atStartOfDay(zone).toInstant();
        Map<String, long[]> buckets = new TreeMap<>();
        for (int i = 0; i < span; i++) {
            buckets.put(today.minusDays(i).toString(), new long[4]);
        }
        for (Ride r : store.query(Query.of(Collections.RIDES).where("createdAt", Query.Op.GTE, from).limit(20_000), Ride.class)) {
            Instant at = r.status() == RideStatus.COMPLETED && r.timeline().completedAt() != null
                    ? r.timeline().completedAt() : r.createdAt();
            long[] b = buckets.get(LocalDate.ofInstant(at, zone).toString());
            if (b == null) {
                continue;
            }
            if (r.status() == RideStatus.COMPLETED) {
                b[0]++;
                b[2] += r.fare().totalCents();
                b[3] += r.fare().commissionCents();
            } else if (r.status() == RideStatus.CANCELLED) {
                b[1]++;
            }
        }
        List<DayPoint> out = new ArrayList<>();
        buckets.forEach((d, b) -> out.add(new DayPoint(d, b[0], b[1], b[2], b[3])));
        return out;
    }

    public Map<String, Object> alerts() {
        Instant now = Instant.now(clock);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("driversPendingReview", store.count(Query.of(Collections.DRIVER_PROFILES).eq("status", DriverStatus.PENDING_REVIEW)));
        m.put("driversDocumentsExpired", store.count(Query.of(Collections.DRIVER_PROFILES).eq("status", DriverStatus.DOCUMENTS_EXPIRED)));
        m.put("ridesSearchingLong", store.query(Query.of(Collections.RIDES).eq("status", RideStatus.SEARCHING_DRIVER).limit(500),
                Ride.class).stream().filter(r -> r.createdAt().isBefore(now.minus(Duration.ofMinutes(2)))).count());
        m.put("reportedRatings", store.count(Query.of(Collections.RATINGS).eq("reported", true)));
        m.put("expiringCoupons", store.query(Query.of(Collections.COUPONS).eq("active", true), Coupon.class).stream()
                .filter(c -> c.endsAt() != null && c.endsAt().isBefore(now.plus(Duration.ofDays(2)))).count());
        return m;
    }

}
