package com.lucenadrive.presence;

import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Localização (O olho de Deus)")
public class PresenceController {

    private final PresenceService presence;

    public PresenceController(PresenceService presence) {
        this.presence = presence;
    }

    public record ConsentRequest(boolean enabled) {
    }

    public record ReportRequest(@NotNull Presence.Kind app, double lat, double lng, Double accuracyM) {
    }

    @GetMapping("/api/v1/me/location-sharing")
    public PresenceService.ConsentView consent(@AuthenticationPrincipal AuthUser user) {
        return presence.consent(user);
    }

    @PutMapping("/api/v1/me/location-sharing")
    public PresenceService.ConsentView setConsent(@AuthenticationPrincipal AuthUser user,
                                                  @RequestBody ConsentRequest body) {
        return presence.setConsent(user, body.enabled());
    }

    @PostMapping("/api/v1/me/presence")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ReportRequest body) {
        presence.report(user, body.app(), body.lat(), body.lng(), body.accuracyM());
    }

    @GetMapping("/api/v1/admin/god-eye/positions")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    public List<PresenceService.LivePosition> live(@AuthenticationPrincipal AuthUser staff,
                                                   @RequestParam(required = false) Presence.Kind kind,
                                                   @RequestParam(required = false) String cityId) {
        return presence.live(staff, kind, cityId);
    }
}
