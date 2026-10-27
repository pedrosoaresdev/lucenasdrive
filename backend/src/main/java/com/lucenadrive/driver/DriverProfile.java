package com.lucenadrive.driver;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * driverProfiles/{uid}. Agregados de avaliação (ratingSum/ratingCount/starCounts) são mantidos
 * exclusivamente pelo backend na mesma transação que grava cada avaliação.
 */
public record DriverProfile(
        String id,
        DriverStatus status,
        String cnhNumberMasked,
        String cnhCategory,
        String cnhExpiresOn,
        boolean cnhEar,
        List<DriverDocument> documents,
        String cityId,
        String activeVehicleId,
        long ratingSum,
        long ratingCount,
        double ratingAvg,
        Map<String, Long> starCounts,
        long completedRides,
        long cancelledRides,
        long warnings,
        String statusReason,
        Instant submittedAt,
        Instant reviewedAt,
        String reviewedBy,
        Instant createdAt,
        Instant updatedAt) {

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Builder para cópias com alterações pontuais (records com muitos campos). */
    public static final class Builder {
        private DriverStatus status;
        private List<DriverDocument> documents;
        private String cityId;
        private String activeVehicleId;
        private long ratingSum;
        private long ratingCount;
        private double ratingAvg;
        private Map<String, Long> starCounts;
        private long completedRides;
        private long cancelledRides;
        private long warnings;
        private String statusReason;
        private Instant submittedAt;
        private Instant reviewedAt;
        private String reviewedBy;
        private Instant updatedAt;
        private final DriverProfile base;

        Builder(DriverProfile p) {
            base = p;
            status = p.status;
            documents = p.documents;
            cityId = p.cityId;
            activeVehicleId = p.activeVehicleId;
            ratingSum = p.ratingSum;
            ratingCount = p.ratingCount;
            ratingAvg = p.ratingAvg;
            starCounts = p.starCounts;
            completedRides = p.completedRides;
            cancelledRides = p.cancelledRides;
            warnings = p.warnings;
            statusReason = p.statusReason;
            submittedAt = p.submittedAt;
            reviewedAt = p.reviewedAt;
            reviewedBy = p.reviewedBy;
            updatedAt = p.updatedAt;
        }

        public Builder status(DriverStatus v, String reason) { status = v; statusReason = reason; return this; }
        public Builder documents(List<DriverDocument> v) { documents = v; return this; }
        public Builder cityId(String v) { cityId = v; return this; }
        public Builder activeVehicleId(String v) { activeVehicleId = v; return this; }
        public Builder rating(long sum, long count, Map<String, Long> stars) {
            ratingSum = sum;
            ratingCount = count;
            ratingAvg = count == 0 ? 0 : Math.round((double) sum / count * 100.0) / 100.0;
            starCounts = stars;
            return this;
        }
        public Builder completedRides(long v) { completedRides = v; return this; }
        public Builder cancelledRides(long v) { cancelledRides = v; return this; }
        public Builder warnings(long v) { warnings = v; return this; }
        public Builder submittedAt(Instant v) { submittedAt = v; return this; }
        public Builder reviewed(String by, Instant at) { reviewedBy = by; reviewedAt = at; return this; }
        public Builder updatedAt(Instant v) { updatedAt = v; return this; }

        public DriverProfile build() {
            return new DriverProfile(base.id, status, base.cnhNumberMasked, base.cnhCategory, base.cnhExpiresOn,
                    base.cnhEar, documents, cityId, activeVehicleId, ratingSum, ratingCount, ratingAvg, starCounts,
                    completedRides, cancelledRides, warnings, statusReason, submittedAt, reviewedAt, reviewedBy,
                    base.createdAt, updatedAt);
        }
    }
}
