package com.lucenadrive.ride;

import com.lucenadrive.fare.VehicleCategory;
import java.time.Instant;

/**
 * rideOffers/{rideId}_{driverId}: oferta enviada a um motorista. Legível apenas pelo motorista
 * destinatário (listener). Contém só o necessário para decidir: bairro/endereço de embarque,
 * distância até o embarque e ganho estimado.
 */
public record RideOffer(String id, String rideId, String driverId, Status status, VehicleCategory category,
                        String pickupAddress, String destinationAddress, double pickupLat, double pickupLng,
                        long distanceToPickupMeters, long tripDistanceMeters, long tripDurationSeconds,
                        long estimatedEarningCents, String passengerName, Double passengerRating,
                        Instant expiresAt, Instant createdAt, Instant respondedAt) {

    public enum Status { PENDING, ACCEPTED, DECLINED, EXPIRED, SUPERSEDED }

    public static String idFor(String rideId, String driverId) {
        return rideId + "_" + driverId;
    }

    public RideOffer withStatus(Status s, Instant at) {
        return new RideOffer(id, rideId, driverId, s, category, pickupAddress, destinationAddress, pickupLat, pickupLng,
                distanceToPickupMeters, tripDistanceMeters, tripDurationSeconds, estimatedEarningCents, passengerName,
                passengerRating, expiresAt, createdAt, at);
    }
}
