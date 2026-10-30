package com.lucenadrive.geo;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.config.LucenaProperties;
import org.springframework.stereotype.Service;

@Service
public class RouteService {

    private final RouteProvider provider;
    private final boolean realRoutes;

    /** Ordem: Google Routes (se houver chave) → OSRM/OpenStreetMap (se configurado) → linha reta (se permitido). */
    public RouteService(LucenaProperties props) {
        java.util.List<RouteProvider> chain = new java.util.ArrayList<>();
        String key = props.maps().serverApiKey();
        if (key != null && !key.isBlank()) {
            chain.add(new GoogleRoutesProvider(key));
        }
        String osrm = props.maps().osrmUrl();
        if (osrm != null && !osrm.isBlank()) {
            chain.add(new OsrmRouteProvider(osrm));
        }
        realRoutes = !chain.isEmpty();
        if (props.maps().allowStraightLineFallback()) {
            chain.add(new StraightLineRouteProvider());
        }
        provider = chain.isEmpty() ? null : new FallbackRouteProvider(chain);
    }

    /** Há provedor de rota real (Google ou OSRM) configurado. */
    public boolean configured() {
        return realRoutes;
    }

    /** Tempos de chegada (s) de cada origem ao destino; nulo para quem não tem rota. Lança se nada estiver disponível. */
    public java.util.List<Long> etaSeconds(java.util.List<GeoPoint> origins, GeoPoint destination) {
        if (provider == null) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED, "Cálculo de rotas indisponível.");
        }
        return provider.durationsTo(origins, destination);
    }

    public boolean available() {
        return provider != null;
    }

    public RouteResult route(GeoPoint origin, GeoPoint destination) {
        if (provider == null) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED,
                    "Cálculo de rotas indisponível: configure MAPS_SERVER_API_KEY (Google) ou MAPS_OSRM_URL (OpenStreetMap).");
        }
        return provider.route(origin, destination);
    }
}
