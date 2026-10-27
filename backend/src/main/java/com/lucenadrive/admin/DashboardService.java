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
                          FinanceService.PlatformFinance finance, List<CityCount> ridesByCity, Instant generatedAt) {
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
        long passengers = store.count(Query.of(Collections.USERS).where("roles", Query.Op.ARRAY_CONTAINS, Role.PASSENGER));
        long drivers = store.count(Query.of(Collections.DRIVER_PROFILES));
        long approved = store.count(Query.of(Collections.DRIVER_PROFILES).eq("status", DriverStatus.APPROVED));
        long pending = store.count(Query.of(Collections.DRIVER_PROFILES).eq("status", DriverStatus.PENDING_REVIEW));
        long online = store.count(Query.of(Collections.DRIVER_LOCATIONS).eq("online", true));
        long searching = store.count(Query.of(Collections.RIDES).eq("status", RideStatus.SEARCHING_DRIVER));
        long inProgress = store.count(Query.of(Collections.RIDES).where("status", Query.Op.IN, RideStatus.WITH_DRIVER));
        long completed = store.count(Query.of(Collections.RIDES).eq("status", RideStatus.COMPLETED));
        long cancelled = store.count(Query.of(Collections.RIDES).eq("status", RideStatus.CANCELLED));
        long coupons = store.count(Query.of(Collections.COUPONS).eq("active", true));
        Map<String, Object> rs = ratings.platformStats();
        long pendingPayouts = store.count(Query.of(Collections.PAYOUT_REQUESTS).where("status", Query.Op.IN,
                List.of(PayoutRequest.Status.REQUESTED, PayoutRequest.Status.IN_VALIDATION, PayoutRequest.Status.PROCESSING)));
        long safety = store.count(Query.of(Collections.SUPPORT_TICKETS).eq("category", SupportTicket.Category.SAFETY)
                .where("status", Query.Op.IN, List.of(SupportTicket.Status.OPEN, SupportTicket.Status.IN_PROGRESS,
                        SupportTicket.Status.ESCALATED)));
        long issues = finance.reconciliation().size();
        FinanceService.PlatformFinance fin = finance.platform(Instant.EPOCH, now.plusSeconds(1));
        List<CityCount> byCity = new ArrayList<>();
        for (City c : store.query(Query.of(Collections.CITIES), City.class)) {
            byCity.add(new CityCount(c.id(), c.name(),
                    store.count(Query.of(Collections.RIDES).eq("cityId", c.id()).eq("status", RideStatus.COMPLETED)),
                    store.count(Query.of(Collections.RIDES).eq("cityId", c.id()).where("status", Query.Op.IN, RideStatus.ACTIVE))));
        }
        return new Summary(passengers, drivers, approved, pending, online, searching, inProgress, completed, cancelled,
                coupons, ((Number) rs.get("average")).doubleValue(), ((Number) rs.get("count")).longValue(),
                pendingPayouts, safety, issues, fin, byCity, now);
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
