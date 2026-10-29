package com.lucenadrive.ride;

import com.lucenadrive.fare.FareBreakdown;
import com.lucenadrive.fare.VehicleCategory;
import com.lucenadrive.geo.Place;
import com.lucenadrive.geo.RouteResult;
import com.lucenadrive.payment.PaymentMethod;
import com.lucenadrive.payment.PaymentStatus;
import java.time.Instant;
import java.util.List;

/**
 * rides/{id}. Documento legível pelos dois participantes (listener em tempo real) e gravado
 * somente pelo backend. Não contém CPF, telefone nem dados internos de despacho.
 */
public record Ride(
        String id,
        String code,
        String passengerId,
        String driverId,
        List<String> participantIds,
        RideStatus status,
        VehicleCategory category,
        String cityId,
        Place origin,
        Place destination,
        RouteInfo route,
        FareBreakdown fare,
        String couponCode,
        PaymentMethod paymentMethod,
        PaymentStatus paymentStatus,
        PartySnapshot passenger,
        DriverSnapshot driver,
        LivePosition driverPosition,
        Long etaSeconds,
        Timeline timeline,
        Cancellation cancellation,
        RatingState rating,
        String quoteId,
        Instant createdAt,
        Instant updatedAt,
        RatingState driverRating) {

    public record RouteInfo(long distanceMeters, long durationSeconds, String encodedPolyline,
                            RouteResult.Source source) {
    }

    public record PartySnapshot(String userId, String name, String photoPath, Double rating) {
    }

    public record DriverSnapshot(String userId, String name, String photoPath, double ratingAvg, long ratingCount,
                                 String vehicleId, String vehicleMake, String vehicleModel, String vehicleColor,
                                 String plate, int vehicleYear) {
    }

    public record LivePosition(double lat, double lng, Double heading, Instant updatedAt) {
    }

    public record Timeline(Instant requestedAt, Instant acceptedAt, Instant arrivingAt, Instant arrivedAt,
                           Instant startedAt, Instant completedAt, Instant cancelledAt) {

        public Timeline at(RideStatus status, Instant t) {
            return switch (status) {
                case ACCEPTED -> new Timeline(requestedAt, t, arrivingAt, arrivedAt, startedAt, completedAt, cancelledAt);
                case DRIVER_ARRIVING -> new Timeline(requestedAt, acceptedAt, t, arrivedAt, startedAt, completedAt, cancelledAt);
                case DRIVER_ARRIVED -> new Timeline(requestedAt, acceptedAt, arrivingAt, t, startedAt, completedAt, cancelledAt);
                case IN_PROGRESS -> new Timeline(requestedAt, acceptedAt, arrivingAt, arrivedAt, t, completedAt, cancelledAt);
                case COMPLETED -> new Timeline(requestedAt, acceptedAt, arrivingAt, arrivedAt, startedAt, t, cancelledAt);
                case CANCELLED -> new Timeline(requestedAt, acceptedAt, arrivingAt, arrivedAt, startedAt, completedAt, t);
                default -> this;
            };
        }
    }

    public record Cancellation(String byUserId, ActorRole byRole, String reasonCode, String reasonText,
                               long feeCents) {
    }

    public enum RatingStatus { NOT_APPLICABLE, PENDING, SUBMITTED, SKIPPED }

    public record RatingState(RatingStatus status, Integer stars) {
    }

    public boolean isParticipant(String uid) {
        return uid != null && (uid.equals(passengerId) || uid.equals(driverId));
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private final Ride b;
        private String driverId;
        private List<String> participantIds;
        private RideStatus status;
        private PaymentStatus paymentStatus;
        private DriverSnapshot driver;
        private LivePosition driverPosition;
        private Long etaSeconds;
        private Timeline timeline;
        private Cancellation cancellation;
        private RatingState rating;
        private RatingState driverRating;
        private FareBreakdown fare;
        private Instant updatedAt;

        Builder(Ride r) {
            b = r;
            driverId = r.driverId;
            participantIds = r.participantIds;
            status = r.status;
            paymentStatus = r.paymentStatus;
            driver = r.driver;
            driverPosition = r.driverPosition;
            etaSeconds = r.etaSeconds;
            timeline = r.timeline;
            cancellation = r.cancellation;
            rating = r.rating;
            driverRating = r.driverRating;
            fare = r.fare;
            updatedAt = r.updatedAt;
        }

        public Builder driver(String id, DriverSnapshot snap) {
            driverId = id;
            driver = snap;
            participantIds = List.of(b.passengerId, id);
            return this;
        }
        /** Remove o motorista e volta à busca, preservando o horário da solicitação (redespacho). */
        public Builder backToSearch(Instant at) {
            driverId = null;
            driver = null;
            participantIds = List.of(b.passengerId);
            driverPosition = null;
            etaSeconds = null;
            status = RideStatus.SEARCHING_DRIVER;
            timeline = new Timeline(timeline.requestedAt(), null, null, null, null, null, null);
            updatedAt = at;
            return this;
        }
        public Builder status(RideStatus v, Instant at) {
            status = v;
            timeline = timeline.at(v, at);
            updatedAt = at;
            return this;
        }
        public Builder paymentStatus(PaymentStatus v) { paymentStatus = v; return this; }
        public Builder driverPosition(LivePosition v) { driverPosition = v; return this; }
        public Builder etaSeconds(Long v) { etaSeconds = v; return this; }
        public Builder cancellation(Cancellation v) { cancellation = v; return this; }
        public Builder rating(RatingState v) { rating = v; return this; }
        /** Avaliação recíproca: motorista → passageiro. */
        public Builder driverRating(RatingState v) { driverRating = v; return this; }
        public Builder fare(FareBreakdown v) { fare = v; return this; }
        public Builder updatedAt(Instant v) { updatedAt = v; return this; }

        public Ride build() {
            return new Ride(b.id, b.code, b.passengerId, driverId, participantIds, status, b.category, b.cityId,
                    b.origin, b.destination, b.route, fare, b.couponCode, b.paymentMethod, paymentStatus, b.passenger,
                    driver, driverPosition, etaSeconds, timeline, cancellation, rating, b.quoteId, b.createdAt,
                    updatedAt, driverRating);
        }
    }
}
