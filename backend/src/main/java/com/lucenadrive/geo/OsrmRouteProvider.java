package com.lucenadrive.geo;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Rotas pelo OSRM (OpenStreetMap), sem chave e sem custo. O servidor público de demonstração
 * (router.project-osrm.org) tem limite de uso (~1 requisição/s) e não oferece garantia: para operação em
 * escala, hospede um OSRM próprio e aponte MAPS_OSRM_URL para ele. Distância/tempo sem trânsito em tempo real.
 */
public class OsrmRouteProvider implements RouteProvider {

    private static final long MIN_INTERVAL_MS = 1_100;
    private static final long MAX_WAIT_MS = 2_500;

    private final RestClient client;
    private final String baseUrl;
    private long lastCall;

    public OsrmRouteProvider(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(6));
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.client = RestClient.builder().requestFactory(factory)
                .defaultHeader("User-Agent", "LucenaDrive/0.1 (plataforma de mobilidade; contato via app)")
                .build();
    }

    /** Respeita o intervalo mínimo entre chamadas; se a espera for longa demais, desiste (o chamador usa o plano B). */
    private synchronized void throttle() {
        long wait = lastCall + MIN_INTERVAL_MS - System.currentTimeMillis();
        if (wait > MAX_WAIT_MS) {
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Serviço de rotas ocupado.");
        }
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Cálculo de rota interrompido.");
            }
        }
        lastCall = System.currentTimeMillis();
    }

    @Override
    @SuppressWarnings("unchecked")
    public RouteResult route(GeoPoint origin, GeoPoint destination) {
        throttle();
        String coords = String.format(Locale.ROOT, "%.6f,%.6f;%.6f,%.6f",
                origin.lng(), origin.lat(), destination.lng(), destination.lat());
        String url = baseUrl + "/route/v1/driving/" + coords + "?overview=full&geometries=polyline&alternatives=false&steps=false";
        Map<String, Object> res;
        try {
            res = client.get().uri(url).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao calcular a rota.");
        }
        return parse(res);
    }

    /** Matriz do OSRM (/table): tempos de várias origens até um destino em UMA consulta. */
    @Override
    @SuppressWarnings("unchecked")
    public List<Long> durationsTo(List<GeoPoint> origins, GeoPoint destination) {
        if (origins.isEmpty()) {
            return List.of();
        }
        throttle();
        StringBuilder coords = new StringBuilder(String.format(Locale.ROOT, "%.6f,%.6f", destination.lng(), destination.lat()));
        StringBuilder sources = new StringBuilder();
        for (int i = 0; i < origins.size(); i++) {
            GeoPoint o = origins.get(i);
            coords.append(String.format(Locale.ROOT, ";%.6f,%.6f", o.lng(), o.lat()));
            sources.append(i == 0 ? "" : ";").append(i + 1);
        }
        String url = baseUrl + "/table/v1/driving/" + coords + "?sources=" + sources + "&destinations=0&annotations=duration";
        Map<String, Object> res;
        try {
            res = client.get().uri(url).retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao calcular tempos de chegada.");
        }
        return parseTable(res, origins.size());
    }

    @SuppressWarnings("unchecked")
    static List<Long> parseTable(Map<String, Object> res, int n) {
        if (res == null || !"Ok".equals(res.get("code"))) {
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao calcular tempos de chegada.");
        }
        List<List<Object>> durations = (List<List<Object>>) res.get("durations");
        if (durations == null || durations.size() != n) {
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Resposta inesperada do serviço de rotas.");
        }
        java.util.ArrayList<Long> out = new java.util.ArrayList<>();
        for (List<Object> row : durations) {
            Object v = row == null || row.isEmpty() ? null : row.get(0);
            out.add(v instanceof Number num ? Math.round(num.doubleValue()) : null);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static RouteResult parse(Map<String, Object> res) {
        if (res == null || !"Ok".equals(res.get("code"))) {
            String code = res == null ? "sem resposta" : String.valueOf(res.get("code"));
            if ("NoRoute".equals(code)) {
                throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA, "Nenhuma rota encontrada entre os pontos.");
            }
            throw new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao calcular a rota (" + code + ").");
        }
        List<Map<String, Object>> routes = (List<Map<String, Object>>) res.get("routes");
        if (routes == null || routes.isEmpty()) {
            throw new ApiException(ErrorCode.OUTSIDE_SERVICE_AREA, "Nenhuma rota encontrada entre os pontos.");
        }
        Map<String, Object> r = routes.get(0);
        long distance = Math.round(((Number) r.getOrDefault("distance", 0)).doubleValue());
        long seconds = Math.round(((Number) r.getOrDefault("duration", 0)).doubleValue());
        Object geometry = r.get("geometry");
        return new RouteResult(distance, seconds, geometry instanceof String g ? g : null, RouteResult.Source.OSRM);
    }
}
