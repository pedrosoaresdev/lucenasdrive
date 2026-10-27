package com.lucenadrive.geo;

/**
 * Estimativa APROXIMADA para desenvolvimento sem chave da Routes API: distância em linha reta ×
 * fator de sinuosidade e velocidade média urbana. Sempre marcada como STRAIGHT_LINE_ESTIMATE e
 * desabilitada em produção (lucena.maps.allow-straight-line-fallback=false).
 */
public class StraightLineRouteProvider implements RouteProvider {

    static final double DETOUR_FACTOR = 1.35;
    static final double AVG_SPEED_MPS = 25_000.0 / 3600.0;

    @Override
    public RouteResult route(GeoPoint origin, GeoPoint destination) {
        long meters = Math.round(GeoMath.haversineMeters(origin, destination) * DETOUR_FACTOR);
        long seconds = Math.max(60, Math.round(meters / AVG_SPEED_MPS));
        return new RouteResult(meters, seconds, null, RouteResult.Source.STRAIGHT_LINE_ESTIMATE);
    }
}
