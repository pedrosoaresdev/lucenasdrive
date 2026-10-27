package com.lucenadrive.ride;

import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/driver")
@PreAuthorize("hasRole('DRIVER')")
@Tag(name = "Corridas (motorista)")
public class DriverRideController {

    private final DispatchService dispatch;
    private final RideService rides;
    private final LocationService locations;

    public DriverRideController(DispatchService dispatch, RideService rides, LocationService locations) {
        this.dispatch = dispatch;
        this.rides = rides;
        this.locations = locations;
    }

    public record AdvanceRequest(@NotNull RideStatus status) {
    }

    public record LocationRequest(@DecimalMin("-90") @DecimalMax("90") double lat,
                                  @DecimalMin("-180") @DecimalMax("180") double lng,
                                  Double heading, Double speedMps, Double accuracyM) {
    }

    public record CancelRequest(@Size(max = 40) String reasonCode, @Size(max = 300) String reasonText) {
    }

    @GetMapping("/offers")
    public List<RideOffer> offers(@AuthenticationPrincipal AuthUser user) {
        return dispatch.pendingOffers(user.uid());
    }

    @PostMapping("/offers/{rideId}/accept")
    public RideView accept(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId) {
        return dispatch.accept(user, rideId);
    }

    @PostMapping("/offers/{rideId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId) {
        dispatch.decline(user, rideId);
    }

    /** Avança a corrida: DRIVER_ARRIVING, DRIVER_ARRIVED, IN_PROGRESS ou COMPLETED. */
    @PostMapping("/rides/{rideId}/status")
    public RideView advance(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId,
                            @Valid @RequestBody AdvanceRequest body) {
        return rides.driverAdvance(user, rideId, body.status());
    }

    @PostMapping("/rides/{rideId}/cancel")
    public RideView cancel(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId,
                           @Valid @RequestBody CancelRequest body) {
        return rides.cancelByDriver(user, rideId, body.reasonCode(), body.reasonText());
    }

    @PostMapping("/location")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void location(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody LocationRequest body) {
        locations.update(user, new LocationService.LocationUpdate(body.lat(), body.lng(), body.heading(),
                body.speedMps(), body.accuracyM()));
    }
}
