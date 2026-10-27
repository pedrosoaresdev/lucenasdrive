package com.lucenadrive.geo;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.config.LucenaProperties;
import org.springframework.stereotype.Service;

@Service
public class RouteService {

    private final RouteProvider provider;

    public RouteService(LucenaProperties props) {
        String key = props.maps().serverApiKey();
        if (key != null && !key.isBlank()) {
            provider = new GoogleRoutesProvider(key);
        } else if (props.maps().allowStraightLineFallback()) {
            provider = new StraightLineRouteProvider();
        } else {
            provider = null;
        }
    }

    public boolean configured() {
        return provider instanceof GoogleRoutesProvider;
    }

    public boolean available() {
        return provider != null;
    }

    public RouteResult route(GeoPoint origin, GeoPoint destination) {
        if (provider == null) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED,
                    "Cálculo de rotas indisponível: configure a Google Routes API (MAPS_SERVER_API_KEY).");
        }
        return provider.route(origin, destination);
    }
}
