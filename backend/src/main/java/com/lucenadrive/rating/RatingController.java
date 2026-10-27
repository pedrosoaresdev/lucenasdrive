package com.lucenadrive.rating;

import com.lucenadrive.security.AuthUser;
import com.lucenadrive.support.SupportTicket;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@Tag(name = "Avaliações")
public class RatingController {

    private final RatingService ratings;

    public RatingController(RatingService ratings) {
        this.ratings = ratings;
    }

    public record RatingRequest(@Min(1) @Max(5) int stars, @Size(max = 2000) String comment,
                                @Size(max = 6) List<String> tags) {
    }

    public record DisputeRequest(@NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    @PostMapping("/api/v1/rides/{rideId}/rating")
    @PreAuthorize("hasRole('PASSENGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public Rating rate(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId,
                       @Valid @RequestBody RatingRequest body) {
        return ratings.submit(user, rideId, body.stars(), body.comment(), body.tags());
    }

    @PostMapping("/api/v1/rides/{rideId}/rating/skip")
    @PreAuthorize("hasRole('PASSENGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void skip(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId) {
        ratings.skip(user, rideId);
    }

    @PostMapping("/api/v1/driver/rides/{rideId}/passenger-rating")
    @PreAuthorize("hasRole('DRIVER')")
    @ResponseStatus(HttpStatus.CREATED)
    public Rating ratePassenger(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId,
                                @Valid @RequestBody RatingRequest body) {
        Rating r = ratings.submitByDriver(user, rideId, body.stars(), body.comment(), body.tags());
        return new Rating(r.id(), r.rideId(), null, r.raterRole(), null, r.rateeRole(), r.stars(), r.comment(), r.tags(),
                r.status(), false, List.of(), r.createdAt(), r.updatedAt());
    }

    @GetMapping("/api/v1/passengers/me/rating")
    @PreAuthorize("hasRole('PASSENGER')")
    public RatingService.PassengerRatingSummary myPassengerRating(@AuthenticationPrincipal AuthUser user) {
        return ratings.passengerSummary(user.uid());
    }

    @GetMapping("/api/v1/drivers/{driverId}/ratings/summary")
    public RatingService.DriverRatingSummary summary(@PathVariable String driverId) {
        return ratings.driverSummary(driverId);
    }

    @GetMapping("/api/v1/drivers/me/ratings")
    @PreAuthorize("hasRole('DRIVER')")
    public List<Rating> mine(@AuthenticationPrincipal AuthUser user) {
        // O motorista vê notas e comentários recebidos, mas não quem avaliou.
        return ratings.list(null, null, user.uid(), 50).stream()
                .filter(r -> r.rateeRole() == Rating.RaterRole.DRIVER)
                .map(r -> new Rating(r.id(), r.rideId(), null, r.raterRole(), r.rateeId(), r.rateeRole(), r.stars(),
                        r.status() == Rating.Status.VISIBLE ? r.comment() : null, r.tags(), r.status(), r.reported(),
                        List.of(), r.createdAt(), r.updatedAt()))
                .toList();
    }

    @PostMapping("/api/v1/drivers/me/ratings/{ratingId}/dispute")
    @PreAuthorize("hasRole('DRIVER')")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportTicket dispute(@AuthenticationPrincipal AuthUser user, @PathVariable String ratingId,
                                 @Valid @RequestBody DisputeRequest body) {
        return ratings.dispute(user, ratingId, body.reason());
    }
}
