package com.lucenadrive.geo;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente da Google Routes API (computeRoutes). Usa a chave de SERVIDOR, restrita por IP/API.
 * Documentação: https://developers.google.com/maps/documentation/routes
 */
public class GoogleRoutesProvider implements RouteProvider {

    private static final String URL = "https://routes.googleapis.com/directions/v2:computeRoutes";

    private static final String MATRIX_URL = "https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix";

    private final RestClient client;
    private final RestClient matrixClient;

    public GoogleRoutesProvider(String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(6));
        this.client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("X-Goog-Api-Key", apiKey)
                .defaultHeader("X-Goog-FieldMask", "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline")
                .build();
        this.matrixClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("X-Goog-Api-Key", apiKey)
                .defaultHeader("X-Goog-FieldMask", "originIndex,duration,condition")
                .build();
    }

    /** Matriz de rotas do Google (computeRouteMatrix), com trânsito: tempos de várias origens até o embarque. */
    @Override
    @SuppressWarnings("unchecked")
    public List<Long> durationsTo(List<GeoPoint> origins, GeoPoint destination) {
        if (origins.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = Map.of(
                "origins", origins.stream().map(o -> Map.of("waypoint", waypoint(o))).toList(),
                "destinations", List.of(Map.of("waypoint", waypoint(destination))),
                "travelMode", "DRIVE", "routingPreference", "TRAFFIC_AWARE");
        try {
            List<Map<String, Object>> res = matrixClient.post().uri(MATRIX_URL).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(List.class);
            Long[] out = new Long[origins.size()];
            for (Map<String, Object> el : res == null ? List.<Map<String, Object>>of() : res) {
                int idx = ((Number) el.getOrDefault("originIndex", 0)).intValue();
                if (idx >= 0 && idx < out.length && "ROUTE_EXISTS".equals(el.get("condition")) && el.get("duration") != null) {
                    out[idx] = Long.parseLong(String.valueOf(el.get("duration")).replace("s", "").split("\\.")[0]);
                }
            }
            return java.util.Arrays.asList(out);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao calcular tempos de chegada.");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public RouteResult route(GeoPoint origin, GeoPoint destination) {
        Map<String, Object> body = Map.of(
                "origin", waypoint(origin),
                "destination", waypoint(destination),
                "travelMode", "DRIVE",
                "routingPreference", "TRAFFIC_AWARE",
                "languageCode", "pt-BR",
                "units", "METRIC");
        try {
            Map<String, Object> res = client.post().uri(URL).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(Map.class);
            List<Map<String, Object>> routes = res == null ? null : (List<Map<String, Object>>) res.get("routes");
            if (routes == null || routes.isEmpty()) {
                throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA, "Nenhuma rota encontrada entre os pontos.");
            }
            Map<String, Object> r = routes.get(0);
            long distance = ((Number) r.getOrDefault("distanceMeters", 0)).longValue();
            String duration = String.valueOf(r.getOrDefault("duration", "0s"));
            long seconds = Long.parseLong(duration.replace("s", "").split("\\.")[0]);
            Map<String, Object> polyline = (Map<String, Object>) r.get("polyline");
            return new RouteResult(distance, seconds,
                    polyline == null ? null : (String) polyline.get("encodedPolyline"), RouteResult.Source.GOOGLE_ROUTES);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao calcular a rota. Tente novamente.");
        }
    }

    private static Map<String, Object> waypoint(GeoPoint p) {
        return Map.of("location", Map.of("latLng", Map.of("latitude", p.lat(), "longitude", p.lng())));
    }
}
