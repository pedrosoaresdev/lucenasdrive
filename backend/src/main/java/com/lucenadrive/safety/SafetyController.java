package com.lucenadrive.safety;

import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.RequestContext;
import com.lucenadrive.support.SupportTicket;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Segurança em viagem")
public class SafetyController {

    private final SafetyService safety;

    public SafetyController(SafetyService safety) {
        this.safety = safety;
    }

    public record AlertRequest(@Size(max = 1000) String description) {
    }

    @PostMapping("/api/v1/rides/{rideId}/share")
    @PreAuthorize("hasRole('PASSENGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public SafetyService.ShareLink share(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId) {
        return safety.share(user, rideId);
    }

    @DeleteMapping("/api/v1/rides/{rideId}/share")
    @PreAuthorize("hasRole('PASSENGER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId) {
        safety.revokeAll(user, rideId);
    }

    @GetMapping("/api/v1/public/ride-shares/{token}")
    public ResponseEntity<SafetyService.SharedRideView> view(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(safety.view(token, String.valueOf(RequestContext.clientIp())));
    }

    @PostMapping("/api/v1/rides/{rideId}/safety-alert")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportTicket alert(@AuthenticationPrincipal AuthUser user, @PathVariable String rideId,
                               @Valid @RequestBody(required = false) AlertRequest body) {
        return safety.alert(user, rideId, body == null ? null : body.description());
    }
}
