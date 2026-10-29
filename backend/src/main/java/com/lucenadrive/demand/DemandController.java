package com.lucenadrive.demand;

import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.SecurityGuards;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Demanda (preço dinâmico e mapa de calor)")
public class DemandController {

    private final DemandService demand;
    private final SecurityGuards guards;

    public DemandController(DemandService demand, SecurityGuards guards) {
        this.demand = demand;
        this.guards = guards;
    }

    public record PolicyRequest(boolean enabled, @Size(min = 1, max = 6) List<DemandPolicy.Step> steps, long maxBp,
                                int windowMinutes, @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    @GetMapping("/api/v1/driver/demand-heatmap")
    @PreAuthorize("hasRole('DRIVER')")
    public List<DemandService.HeatCell> driverHeatmap(@AuthenticationPrincipal AuthUser driver) {
        return demand.heatmap(demand.driverCity(driver.uid()));
    }

    @GetMapping("/api/v1/admin/cities/{cityId}/demand")
    public DemandService.CityDemand view(@PathVariable String cityId) {
        return demand.view(cityId);
    }

    @GetMapping("/api/v1/admin/cities/{cityId}/demand-heatmap")
    public List<DemandService.HeatCell> adminHeatmap(@PathVariable String cityId) {
        return demand.heatmap(cityId);
    }

    @PutMapping("/api/v1/admin/cities/{cityId}/demand-policy")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public DemandPolicy update(@AuthenticationPrincipal AuthUser admin, @PathVariable String cityId,
                               @Valid @RequestBody PolicyRequest body) {
        guards.requireRecentMfa(admin);
        return demand.update(admin, cityId, body.enabled(), body.steps(), body.maxBp(), body.windowMinutes(), body.reason());
    }

    @PostMapping("/api/v1/admin/cities/{cityId}/demand/recompute")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public DemandState recompute(@PathVariable String cityId) {
        return demand.recompute(cityId);
    }
}
