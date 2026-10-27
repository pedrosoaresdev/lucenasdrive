package com.lucenadrive.admin;

import com.lucenadrive.coupon.Coupon;
import com.lucenadrive.coupon.CouponRedemption;
import com.lucenadrive.coupon.CouponService;
import com.lucenadrive.fare.FareConfig;
import com.lucenadrive.fare.FareConfigVersion;
import com.lucenadrive.fare.FareService;
import com.lucenadrive.fare.VehicleCategory;
import com.lucenadrive.geo.City;
import com.lucenadrive.geo.GeoAdminService;
import com.lucenadrive.geo.GeoPoint;
import com.lucenadrive.geo.ServiceArea;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.SecurityGuards;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Configurações comerciais: gestão geográfica, tarifas e cupons. */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin · Cidades, tarifas e cupons")
public class AdminCatalogController {

    private final GeoAdminService geo;
    private final FareService fares;
    private final CouponService coupons;
    private final SecurityGuards guards;

    public AdminCatalogController(GeoAdminService geo, FareService fares, CouponService coupons, SecurityGuards guards) {
        this.geo = geo;
        this.fares = fares;
        this.coupons = coupons;
        this.guards = guards;
    }

    public record CityRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(min = 2, max = 2) String state,
                              @Size(max = 10) String ibgeCode, @NotNull @Valid GeoPoint center, boolean active,
                              String timezone, @Size(max = 300) String reason) {
        GeoAdminService.CityInput input() {
            return new GeoAdminService.CityInput(name, state, ibgeCode, center, active, timezone);
        }
    }

    public record AreaRequest(@NotBlank String cityId, @NotBlank @Size(max = 80) String name,
                              @NotNull ServiceArea.AreaType type, @NotNull @Size(min = 3, max = 500) List<@Valid GeoPoint> polygon,
                              boolean active, Long fareMultiplierBp, @Size(max = 500) String notes,
                              @Size(max = 300) String reason) {
        GeoAdminService.AreaInput input() {
            return new GeoAdminService.AreaInput(cityId, name, type, polygon, active, fareMultiplierBp, notes);
        }
    }

    public record FareRequest(@NotNull FareService.FareConfigInput config,
                              @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record CouponActiveRequest(boolean active, @Size(max = 300) String reason) {
    }

    public record CouponUpdateRequest(@NotNull CouponService.CouponInput coupon, @Size(max = 300) String reason) {
    }

    // ------------------------------------------------------------- gestão geográfica

    @GetMapping("/cities")
    public List<City> cities() {
        return geo.cities();
    }

    @PostMapping("/cities")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @ResponseStatus(HttpStatus.CREATED)
    public City createCity(@AuthenticationPrincipal AuthUser admin, @Valid @RequestBody CityRequest body) {
        return geo.createCity(admin, body.input());
    }

    @PutMapping("/cities/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public City updateCity(@AuthenticationPrincipal AuthUser admin, @PathVariable String id,
                           @Valid @RequestBody CityRequest body) {
        return geo.updateCity(admin, id, body.input(), body.reason());
    }

    @GetMapping("/cities/{cityId}/areas")
    public List<ServiceArea> areas(@PathVariable String cityId) {
        return geo.areas(cityId);
    }

    @PostMapping("/areas")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceArea createArea(@AuthenticationPrincipal AuthUser admin, @Valid @RequestBody AreaRequest body) {
        return geo.createArea(admin, body.input());
    }

    @PutMapping("/areas/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public ServiceArea updateArea(@AuthenticationPrincipal AuthUser admin, @PathVariable String id,
                                  @Valid @RequestBody AreaRequest body) {
        return geo.updateArea(admin, id, body.input(), body.reason());
    }

    // ------------------------------------------------------------- tarifas

    @GetMapping("/cities/{cityId}/fares")
    public List<FareConfig> fares(@PathVariable String cityId) {
        return fares.listByCity(cityId);
    }

    @PutMapping("/cities/{cityId}/fares/{category}")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public FareConfig upsertFare(@AuthenticationPrincipal AuthUser admin, @PathVariable String cityId,
                                 @PathVariable VehicleCategory category, @Valid @RequestBody FareRequest body) {
        guards.requireRecentMfa(admin);
        return fares.upsert(admin, cityId, category, body.config(), body.reason());
    }

    @GetMapping("/fares/{configId}/history")
    public List<FareConfigVersion> fareHistory(@PathVariable String configId) {
        return fares.history(configId);
    }

    // ------------------------------------------------------------- cupons

    @GetMapping("/coupons")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OWNER')")
    public List<Coupon> coupons(@RequestParam(required = false) Boolean active) {
        return coupons.list(active);
    }

    @PostMapping("/coupons")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @ResponseStatus(HttpStatus.CREATED)
    public Coupon createCoupon(@AuthenticationPrincipal AuthUser admin, @Valid @RequestBody CouponService.CouponInput body) {
        return coupons.create(admin, body);
    }

    @PutMapping("/coupons/{code}")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public Coupon updateCoupon(@AuthenticationPrincipal AuthUser admin, @PathVariable String code,
                               @Valid @RequestBody CouponUpdateRequest body) {
        return coupons.update(admin, code, body.coupon(), body.reason());
    }

    @PostMapping("/coupons/{code}/active")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public Coupon setCouponActive(@AuthenticationPrincipal AuthUser admin, @PathVariable String code,
                                  @Valid @RequestBody CouponActiveRequest body) {
        return coupons.setActive(admin, code, body.active(), body.reason());
    }

    @GetMapping("/coupons/{code}/redemptions")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OWNER')")
    public List<CouponRedemption> redemptions(@PathVariable String code) {
        return coupons.redemptions(code);
    }

    @GetMapping("/coupons/{code}/results")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OWNER')")
    public CouponService.CampaignResult results(@PathVariable String code) {
        return coupons.results(code);
    }
}
