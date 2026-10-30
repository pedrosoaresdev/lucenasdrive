package com.lucenadrive.geo;

import java.util.List;

public interface RouteProvider {

    RouteResult route(GeoPoint origin, GeoPoint destination);

    /**
     * Tempo de viagem (s) de cada origem até o destino, pelas ruas. Elemento nulo = sem rota para aquela origem.
     * Implementação padrão: uma rota por origem; provedores com matriz (OSRM/Google) fazem uma só consulta.
     */
    default List<Long> durationsTo(List<GeoPoint> origins, GeoPoint destination) {
        return origins.stream().map(o -> route(o, destination).durationSeconds()).toList();
    }
}
