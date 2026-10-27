package com.lucenadrive.driver;

import com.lucenadrive.fare.VehicleCategory;
import java.time.Instant;
import java.util.List;

/**
 * driverLocations/{uid}: posição operacional. Não contém dados pessoais. Sem leitura pelo cliente:
 * o passageiro recebe a posição do seu motorista pelo documento da corrida.
 * available = online e sem corrida atual (campo explícito para consultas indexadas).
 */
public record DriverLocation(String driverId, boolean online, boolean available, String cityId,
                             List<VehicleCategory> categories, String vehicleId, Double lat, Double lng,
                             Double heading, Double speedMps, Double accuracyM, String currentRideId,
                             Instant onlineSince, Instant updatedAt) {

    public DriverLocation withRide(String rideId, Instant now) {
        return new DriverLocation(driverId, online, online && rideId == null, cityId, categories, vehicleId, lat, lng,
                heading, speedMps, accuracyM, rideId, onlineSince, now);
    }

    public DriverLocation withPosition(double newLat, double newLng, Double newHeading, Double newSpeed,
                                       Double newAccuracy, Instant now) {
        return new DriverLocation(driverId, online, available, cityId, categories, vehicleId, newLat, newLng,
                newHeading, newSpeed, newAccuracy, currentRideId, onlineSince, now);
    }

    public DriverLocation withOnline(boolean value, Instant now) {
        return new DriverLocation(driverId, value, value && currentRideId == null, cityId, categories, vehicleId, lat,
                lng, heading, speedMps, accuracyM, currentRideId, value ? now : null, now);
    }
}
