package com.lucenadrive.admin;

import com.lucenadrive.driver.DocumentType;
import com.lucenadrive.driver.DriverProfile;
import com.lucenadrive.driver.DriverStatus;
import com.lucenadrive.driver.Vehicle;
import com.lucenadrive.finance.FinanceService;
import com.lucenadrive.rating.RatingService;
import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/drivers")
@Tag(name = "Admin · Motoristas")
public class AdminDriversController {

    private final AdminDriverService drivers;
    private final RatingService ratings;
    private final FinanceService finance;

    public AdminDriversController(AdminDriverService drivers, RatingService ratings, FinanceService finance) {
        this.drivers = drivers;
        this.ratings = ratings;
        this.finance = finance;
    }

    public record StatusRequest(@NotNull DriverStatus status, @NotBlank @Size(max = 500) String reason) {
    }

    public record DocumentReview(@NotNull DocumentType type, String vehicleId, boolean approve, @Size(max = 500) String note) {
    }

    public record VehicleStatusRequest(@NotNull Vehicle.Status status, @Size(max = 500) String reason) {
    }

    public record WarningRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public List<DriverProfile> list(@RequestParam(required = false) DriverStatus status,
                                    @RequestParam(required = false) String cityId,
                                    @RequestParam(defaultValue = "50") int limit) {
        return drivers.list(status, cityId, limit);
    }

    @GetMapping("/{driverId}")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public AdminDriverService.DriverDetail detail(@PathVariable String driverId) {
        return drivers.detail(driverId);
    }

    @GetMapping("/{driverId}/ratings")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public RatingService.DriverRatingSummary ratings(@PathVariable String driverId) {
        return ratings.driverSummary(driverId);
    }

    @GetMapping("/{driverId}/finance")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','OWNER')")
    public FinanceService.DriverFinance finance(@PathVariable String driverId) {
        return finance.driver(driverId);
    }

    @PostMapping("/{driverId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public DriverProfile status(@AuthenticationPrincipal AuthUser staff, @PathVariable String driverId,
                                @Valid @RequestBody StatusRequest body) {
        return drivers.changeStatus(staff, driverId, body.status(), body.reason());
    }

    @PostMapping("/{driverId}/documents/review")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public DriverProfile review(@AuthenticationPrincipal AuthUser staff, @PathVariable String driverId,
                                @Valid @RequestBody DocumentReview body) {
        return drivers.reviewDocument(staff, driverId, body.type(), body.vehicleId(), body.approve(), body.note());
    }

    @GetMapping("/{driverId}/documents/url")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public Map<String, String> documentUrl(@AuthenticationPrincipal AuthUser staff, @PathVariable String driverId,
                                           @RequestParam DocumentType type,
                                           @RequestParam(required = false) String vehicleId) {
        return drivers.documentUrl(staff, driverId, type, vehicleId);
    }

    @PostMapping("/vehicles/{vehicleId}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public Vehicle vehicleStatus(@AuthenticationPrincipal AuthUser staff, @PathVariable String vehicleId,
                                 @Valid @RequestBody VehicleStatusRequest body) {
        return drivers.changeVehicleStatus(staff, vehicleId, body.status(), body.reason());
    }

    @PostMapping("/{driverId}/warnings")
    @PreAuthorize("hasAnyRole('SUPPORT','ADMIN','OWNER')")
    public DriverProfile warn(@AuthenticationPrincipal AuthUser staff, @PathVariable String driverId,
                              @Valid @RequestBody WarningRequest body) {
        return drivers.warn(staff, driverId, body.reason());
    }
}
