package com.lucenadrive.driver;

import com.lucenadrive.fare.VehicleCategory;
import java.time.Instant;

/** vehicles/{id}. Placa normalizada (ABC1D23) e única via plateIndex. */
public record Vehicle(String id, String driverId, String make, String model, int year, String color, String plate,
                      VehicleCategory category, Status status, String crlvExpiresOn, String statusReason,
                      Instant createdAt, Instant updatedAt) {

    public enum Status { PENDING_REVIEW, APPROVED, REJECTED, SUSPENDED }

    public Vehicle withStatus(Status s, String reason, Instant now) {
        return new Vehicle(id, driverId, make, model, year, color, plate, category, s, crlvExpiresOn, reason,
                createdAt, now);
    }

    /** Placa para exibição ao passageiro. */
    public String displayPlate() {
        return plate.length() == 7 ? plate.substring(0, 3) + "-" + plate.substring(3) : plate;
    }
}
