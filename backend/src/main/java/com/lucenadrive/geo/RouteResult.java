package com.lucenadrive.geo;

/**
 * Resultado de cálculo de rota. source indica a origem do dado:
 * GOOGLE_ROUTES (Routes API) ou STRAIGHT_LINE_ESTIMATE (somente dev, aproximação explícita).
 */
public record RouteResult(long distanceMeters, long durationSeconds, String encodedPolyline, Source source) {

    public enum Source { GOOGLE_ROUTES, STRAIGHT_LINE_ESTIMATE }
}
