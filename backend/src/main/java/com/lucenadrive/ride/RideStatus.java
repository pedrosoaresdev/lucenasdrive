package com.lucenadrive.ride;

import java.util.EnumSet;
import java.util.Set;

public enum RideStatus {
    REQUESTED, SEARCHING_DRIVER, ACCEPTED, DRIVER_ARRIVING, DRIVER_ARRIVED, IN_PROGRESS, COMPLETED, CANCELLED;

    public static final Set<RideStatus> ACTIVE = EnumSet.of(REQUESTED, SEARCHING_DRIVER, ACCEPTED, DRIVER_ARRIVING,
            DRIVER_ARRIVED, IN_PROGRESS);

    public static final Set<RideStatus> WITH_DRIVER = EnumSet.of(ACCEPTED, DRIVER_ARRIVING, DRIVER_ARRIVED, IN_PROGRESS);

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
