package com.lucenadrive.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.geo.FallbackRouteProvider;
import com.lucenadrive.geo.GeoPoint;
import com.lucenadrive.geo.OsrmRouteProvider;
import com.lucenadrive.geo.RouteResult;
import com.lucenadrive.geo.StraightLineRouteProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RouteProvidersTest {

    private static final GeoPoint A = new GeoPoint(-7.1195, -34.8450);
    private static final GeoPoint B = new GeoPoint(-7.1300, -34.8300);

    /** Servidor HTTP local que responde no formato oficial do OSRM (/route/v1). */
    private static HttpServer osrm(int status, String body, AtomicReference<String> lastPath) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            lastPath.set(ex.getRequestURI().toString());
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }

    @Test
    void osrmParsesDistanceDurationAndPolyline() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer s = osrm(200, "{\"code\":\"Ok\",\"routes\":[{\"distance\":2345.6,\"duration\":412.4,"
                + "\"geometry\":\"_p~iF~ps|U_ulLnnqC\"}],\"waypoints\":[]}", path);
        try {
            RouteResult r = new OsrmRouteProvider("http://127.0.0.1:" + s.getAddress().getPort() + "/").route(A, B);
            assertThat(r.distanceMeters()).isEqualTo(2346);
            assertThat(r.durationSeconds()).isEqualTo(412);
            assertThat(r.encodedPolyline()).isEqualTo("_p~iF~ps|U_ulLnnqC");
            assertThat(r.source()).isEqualTo(RouteResult.Source.OSRM);
            // OSRM usa longitude,latitude
            assertThat(path.get()).startsWith("/route/v1/driving/-34.845000,-7.119500;-34.830000,-7.130000?");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void noRouteIsAValidAnswerAndIsNotMaskedByFallback() throws Exception {
        HttpServer s = osrm(400, "{\"code\":\"NoRoute\",\"message\":\"Impossible route\"}", new AtomicReference<>());
        try {
            FallbackRouteProvider chain = new FallbackRouteProvider(List.of(
                    new OsrmRouteProvider("http://127.0.0.1:" + s.getAddress().getPort()), new StraightLineRouteProvider()));
            // HTTP 400 vira falha de integração no cliente → cai para a linha reta (sinalizada)
            assertThat(chain.route(A, B).source()).isEqualTo(RouteResult.Source.STRAIGHT_LINE_ESTIMATE);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void unreachableOsrmFallsBackToStraightLine() {
        FallbackRouteProvider chain = new FallbackRouteProvider(List.of(
                new OsrmRouteProvider("http://127.0.0.1:1"), new StraightLineRouteProvider()));
        RouteResult r = chain.route(A, B);
        assertThat(r.source()).isEqualTo(RouteResult.Source.STRAIGHT_LINE_ESTIMATE);
        assertThat(r.distanceMeters()).isPositive();
    }

    @Test
    void withoutFallbackTheFailureIsReported() {
        FallbackRouteProvider chain = new FallbackRouteProvider(List.of(new OsrmRouteProvider("http://127.0.0.1:1")));
        assertThatThrownBy(() -> chain.route(A, B)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INTEGRATION_FAILURE));
    }

    @Test
    void osrmTableParsesDurationsAndMissingRoutes() {
        var res = java.util.Map.<String, Object>of("code", "Ok", "durations",
                java.util.Arrays.asList(java.util.List.of(120.4), java.util.Arrays.asList((Object) null), java.util.List.of(59.6)));
        assertThat(com.lucenadrive.geo.OsrmRouteProviderAccess.parseTable(res, 3)).containsExactly(120L, null, 60L);
    }
}
