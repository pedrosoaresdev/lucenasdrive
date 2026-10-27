package com.lucenadrive.geo;

import java.util.List;

public final class GeoMath {

    private static final double EARTH_RADIUS_M = 6_371_000;

    private GeoMath() {
    }

    public static double haversineMeters(GeoPoint a, GeoPoint b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
    }

    /** Ray casting. Polígono como lista de vértices (não precisa repetir o primeiro). */
    public static boolean contains(List<GeoPoint> polygon, GeoPoint p) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            GeoPoint vi = polygon.get(i);
            GeoPoint vj = polygon.get(j);
            boolean crosses = (vi.lat() > p.lat()) != (vj.lat() > p.lat())
                    && p.lng() < (vj.lng() - vi.lng()) * (p.lat() - vi.lat()) / (vj.lat() - vi.lat()) + vi.lng();
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }
}
