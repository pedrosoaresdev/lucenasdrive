package com.lucenadrive.geo;

/**
 * Resultado de cálculo de rota. source indica a origem do dado:
 * GOOGLE_ROUTES (Routes API), OSRM (OpenStreetMap) ou STRAIGHT_LINE_ESTIMATE (aproximação explícita,
 * usada quando os serviços de rota estão indisponíveis).
 */
public record RouteResult(long distanceMeters, long durationSeconds, String encodedPolyline, Source source) {

    public enum Source { GOOGLE_ROUTES, OSRM, STRAIGHT_LINE_ESTIMATE }
}
