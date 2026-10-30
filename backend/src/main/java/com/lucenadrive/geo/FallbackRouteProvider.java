package com.lucenadrive.geo;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tenta os provedores em ordem (ex.: Google → OSRM → estimativa em linha reta). Só passa ao próximo em falha
 * de integração; "sem rota" é resposta válida e é repassada. Assim o passageiro nunca fica sem cotação por
 * indisponibilidade de um serviço externo — a estimativa aproximada é sinalizada na tela.
 */
public class FallbackRouteProvider implements RouteProvider {

    private static final Logger log = LoggerFactory.getLogger(FallbackRouteProvider.class);

    private final List<RouteProvider> providers;

    public FallbackRouteProvider(List<RouteProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public List<RouteProvider> providers() {
        return providers;
    }

    @Override
    public java.util.List<Long> durationsTo(java.util.List<GeoPoint> origins, GeoPoint destination) {
        ApiException last = null;
        for (RouteProvider p : providers) {
            try {
                return p.durationsTo(origins, destination);
            } catch (ApiException e) {
                last = e;
                log.warn("eta_provider_failed provider={} err={}", p.getClass().getSimpleName(), e.getMessage());
            } catch (RuntimeException e) {
                last = new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao calcular tempos de chegada.");
                log.warn("eta_provider_failed provider={} err={}", p.getClass().getSimpleName(), e.toString());
            }
        }
        throw last != null ? last : new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED, "Cálculo de rotas indisponível.");
    }

    @Override
    public RouteResult route(GeoPoint origin, GeoPoint destination) {
        ApiException last = null;
        for (RouteProvider p : providers) {
            try {
                return p.route(origin, destination);
            } catch (ApiException e) {
                if (e.code() != ErrorCode.INTEGRATION_FAILURE) {
                    throw e;
                }
                last = e;
                log.warn("route_provider_failed provider={} err={}", p.getClass().getSimpleName(), e.getMessage());
            } catch (RuntimeException e) {
                last = new ApiException(ErrorCode.INTEGRATION_FAILURE, "Falha ao calcular a rota.");
                log.warn("route_provider_failed provider={} err={}", p.getClass().getSimpleName(), e.toString());
            }
        }
        throw last != null ? last : new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED, "Cálculo de rotas indisponível.");
    }
}
