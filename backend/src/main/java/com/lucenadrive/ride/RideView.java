package com.lucenadrive.ride;

import com.lucenadrive.fare.FareBreakdown;
import com.lucenadrive.fare.VehicleCategory;
import com.lucenadrive.geo.Place;
import com.lucenadrive.payment.PaymentMethod;
import com.lucenadrive.payment.PaymentStatus;
import java.time.Instant;

/** Representação da corrida para a API, filtrada conforme quem consulta. */
public record RideView(
        String id,
        String code,
        RideStatus status,
        VehicleCategory category,
        String categoryName,
        Place origin,
        Place destination,
        Ride.RouteInfo route,
        FareView fare,
        String couponCode,
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        Ride.PartySnapshot passenger,
        Ride.DriverSnapshot driver,
        Ride.LivePosition driverPosition,
        Long etaSeconds,
        Ride.Timeline timeline,
        Ride.Cancellation cancellation,
        Ride.RatingState rating,
        Ride.RatingState driverRating,
        boolean chatAvailable,
        Instant createdAt) {

    /** Passageiro vê valor total/desconto/final; motorista vê também comissão e ganho. */
    public record FareView(long totalCents, long discountCents, long finalCents, Long commissionCents,
                           Long driverEarningCents, long cancellationFeeCents, boolean minimumApplied,
                           long multiplierBp, String fareConfigId, int fareConfigVersion) {
    }

    public enum Viewer { PASSENGER, DRIVER, STAFF }

    public static RideView of(Ride r, Viewer viewer) {
        FareBreakdown f = r.fare();
        boolean seesEarnings = viewer != Viewer.PASSENGER;
        FareView fare = f == null ? null : new FareView(f.totalCents(), f.discountCents(), f.finalCents(),
                seesEarnings ? f.commissionCents() : null, seesEarnings ? f.driverEarningCents() : null,
                f.cancellationFeeCents(), f.minimumApplied(), f.multiplierBp(), f.fareConfigId(), f.fareConfigVersion());
        boolean chat = r.driverId() != null && (RideStatus.WITH_DRIVER.contains(r.status()) || r.status().terminal());
        return new RideView(r.id(), r.code(), r.status(), r.category(), r.category().displayName(), r.origin(),
                r.destination(), r.route(), fare, r.couponCode(), r.paymentMethod(), r.paymentStatus(),
                r.passenger(), r.driver(), RideStatus.WITH_DRIVER.contains(r.status()) ? r.driverPosition() : null,
                r.etaSeconds(), r.timeline(), r.cancellation(), r.rating(),
                viewer == Viewer.PASSENGER ? null : r.driverRating(), chat, r.createdAt());
    }
}
