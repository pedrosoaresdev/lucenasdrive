package com.lucenadrive.driver;

import java.time.Instant;

/** Metadados de documento. O arquivo fica no Storage em área privada (driverDocuments/{uid}/...). */
public record DriverDocument(DocumentType type, String storagePath, String contentType, long sizeBytes,
                             Status status, String expiresOn, String vehicleId, Instant uploadedAt,
                             String reviewedBy, Instant reviewedAt, String reviewNote) {

    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED }

    public DriverDocument reviewed(Status newStatus, String reviewer, Instant at, String note) {
        return new DriverDocument(type, storagePath, contentType, sizeBytes, newStatus, expiresOn, vehicleId,
                uploadedAt, reviewer, at, note);
    }
}
