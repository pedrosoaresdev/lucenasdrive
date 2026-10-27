package com.lucenadrive.geo;

public interface RouteProvider {

    RouteResult route(GeoPoint origin, GeoPoint destination);
}
