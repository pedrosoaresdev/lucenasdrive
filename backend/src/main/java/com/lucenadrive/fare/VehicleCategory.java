package com.lucenadrive.fare;

/** Categorias de serviço do Lucena Drive. */
public enum VehicleCategory {
    ESSENCIAL("Lucena Essencial", 2014, 4),
    CONFORTO("Lucena Conforto", 2018, 4),
    EXECUTIVO("Lucena Executivo", 2020, 4);

    private final String displayName;
    private final int minVehicleYear;
    private final int seats;

    VehicleCategory(String displayName, int minVehicleYear, int seats) {
        this.displayName = displayName;
        this.minVehicleYear = minVehicleYear;
        this.seats = seats;
    }

    public String displayName() {
        return displayName;
    }

    public int minVehicleYear() {
        return minVehicleYear;
    }

    public int seats() {
        return seats;
    }
}
