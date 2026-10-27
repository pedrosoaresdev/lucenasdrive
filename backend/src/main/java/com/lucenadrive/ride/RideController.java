package com.lucenadrive.ride;

import com.lucenadrive.common.web.PageResponse;
import com.lucenadrive.geo.Place;
import com.lucenadrive.payment.PaymentMethod;
import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rides")
@Tag(name = "Corridas (passageiro)")
public class RideController {

    private final RideService rides;

    public RideController(RideService rides) {
        this.rides = rides;
    }

    public record EstimateRequest(@NotNull @Valid Place origin, @NotNull @Valid Place destination,
                                  @Size(max = 30) String couponCode) {
    }

    public record CreateRideRequest(@NotBlank String quoteId, @NotNull PaymentMethod paymentMethod) {
    }

    public record CancelRequest(@Size(max = 40) String reasonCode, @Size(max = 300) String reasonText) {
    }

    @PostMapping("/estimate")
    @PreAuthorize("hasRole('PASSENGER')")
    public RideService.EstimateResult estimate(@AuthenticationPrincipal AuthUser user,
                                               @Valid @RequestBody EstimateRequest body) {
        return rides.estimate(user, body.origin(), body.destination(), body.couponCode());
    }

    @PostMapping
    @PreAuthorize("hasRole('PASSENGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public RideView create(@AuthenticationPrincipal AuthUser user,
                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                           @Valid @RequestBody CreateRideRequest body) {
        return rides.request(user, body.quoteId(), body.paymentMethod(), idempotencyKey);
    }

    @GetMapping("/active")
    public ResponseEntity<RideView> active(@AuthenticationPrincipal AuthUser user) {
        return rides.active(user).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/pending-rating")
    @PreAuthorize("hasRole('PASSENGER')")
    public ResponseEntity<RideView> pendingRating(@AuthenticationPrincipal AuthUser user) {
        return rides.pendingRating(user).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @GetMapping
    public PageResponse<RideView> history(@AuthenticationPrincipal AuthUser user,
                                          @RequestParam(defaultValue = "passenger") String as,
                                          @RequestParam(required = false) RideStatus status,
                                          @RequestParam(required = false) Long cursor,
                                          @RequestParam(defaultValue = "20") int limit) {
        boolean asDriver = "driver".equalsIgnoreCase(as);
        if (asDriver && !user.hasRole(com.lucenadrive.security.Role.DRIVER)) {
            throw com.lucenadrive.common.error.ApiException.forbidden("Somente motoristas.");
        }
        return rides.history(user, asDriver, status, cursor, limit);
    }

    @GetMapping("/{rideId}")
    public RideView get(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId) {
        return rides.get(user, rideId);
    }

    @PostMapping("/{rideId}/cancel")
    @PreAuthorize("hasRole('PASSENGER')")
    public RideView cancel(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId,
                           @Valid @RequestBody CancelRequest body) {
        return rides.cancelByPassenger(user, rideId, body.reasonCode(), body.reasonText());
    }
}
