package com.lucenadrive.geo;

import java.time.Instant;
import java.util.List;

/**
 * serviceAreas/{id}: polígono com finalidade operacional.
 * COVERAGE: área atendida; PICKUP_ZONE: ponto de embarque recomendado; RESTRICTED: proibido
 * embarque/desembarque; FARE_REGION: aplica multiplicador tarifário (pontos-base).
 */
public record ServiceArea(String id, String cityId, String name, AreaType type, List<GeoPoint> polygon,
                          boolean active, Long fareMultiplierBp, String notes, Instant createdAt, Instant updatedAt) {

    public enum AreaType { COVERAGE, PICKUP_ZONE, RESTRICTED, FARE_REGION }
}
