package com.lucenadrive.driver;

public enum DocumentType {
    CNH(true, false),
    CRIMINAL_RECORD(true, false),
    PROFILE_PHOTO(true, false),
    PROOF_OF_ADDRESS(false, false),
    CRLV(false, true);

    private final boolean requiredForDriver;
    private final boolean vehicleDocument;

    DocumentType(boolean requiredForDriver, boolean vehicleDocument) {
        this.requiredForDriver = requiredForDriver;
        this.vehicleDocument = vehicleDocument;
    }

    public boolean requiredForDriver() {
        return requiredForDriver;
    }

    public boolean vehicleDocument() {
        return vehicleDocument;
    }
}
