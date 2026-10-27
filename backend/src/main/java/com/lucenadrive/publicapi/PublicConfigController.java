package com.lucenadrive.publicapi;

import com.lucenadrive.fare.VehicleCategory;
import com.lucenadrive.geo.GeoService;
import com.lucenadrive.geo.RouteService;
import com.lucenadrive.payment.PaymentMethod;
import com.lucenadrive.payment.PaymentProvider;
import com.lucenadrive.rating.RatingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuração pública consumida pelos portais: estado real das integrações (para exibir
 * "integração pendente" em vez de botões sem efeito), categorias e versões dos termos.
 */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "Público")
public class PublicConfigController {

    private final RouteService routes;
    private final PaymentProvider payments;
    private final GeoService geo;
    private final String termsVersion;
    private final String privacyVersion;

    public PublicConfigController(RouteService routes, PaymentProvider payments, GeoService geo,
                                  @Value("${lucena.legal.terms-version:2026-10}") String termsVersion,
                                  @Value("${lucena.legal.privacy-version:2026-10}") String privacyVersion) {
        this.routes = routes;
        this.payments = payments;
        this.geo = geo;
        this.termsVersion = termsVersion;
        this.privacyVersion = privacyVersion;
    }

    public record CategoryInfo(VehicleCategory id, String name, int seats) {
    }

    public record CityInfo(String id, String name, String state, double lat, double lng) {
    }

    public record PublicConfig(boolean routesApiConfigured, boolean approximateRoutesOnly, boolean paymentsConfigured,
                               List<PaymentMethod> paymentMethods, List<CategoryInfo> categories, List<CityInfo> cities,
                               List<String> ratingTags, String termsVersion, String privacyVersion) {
    }

    @GetMapping("/config")
    public PublicConfig config() {
        List<PaymentMethod> methods = Arrays.stream(PaymentMethod.values())
                .filter(m -> !m.requiresProvider() || payments.configured()).toList();
        return new PublicConfig(routes.configured(), routes.available() && !routes.configured(), payments.configured(),
                methods,
                Arrays.stream(VehicleCategory.values()).map(c -> new CategoryInfo(c, c.displayName(), c.seats())).toList(),
                geo.activeCities().stream().map(c -> new CityInfo(c.id(), c.name(), c.state(),
                        c.center() == null ? 0 : c.center().lat(), c.center() == null ? 0 : c.center().lng())).toList(),
                RatingService.ALLOWED_TAGS.stream().sorted().toList(), termsVersion, privacyVersion);
    }
}
