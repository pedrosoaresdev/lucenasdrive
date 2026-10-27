package com.lucenadrive.user;

import com.lucenadrive.notification.NotificationService;
import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Conta", description = "Perfil, preferências, sessões e privacidade do usuário autenticado")
public class MeController {

    private final AccountService accounts;
    private final NotificationService notifications;

    public MeController(AccountService accounts, NotificationService notifications) {
        this.accounts = accounts;
        this.notifications = notifications;
    }

    public record UpdateProfileRequest(
            @NotBlank @Size(min = 5, max = 120) String fullName,
            @NotBlank @Pattern(regexp = "^\\+55\\d{10,11}$", message = "Telefone no formato +55DDDNÚMERO") String phoneNumber,
            @Size(max = 500) String photoPath) {
    }

    public record PushTokenRequest(@NotBlank @Size(max = 4096) String token, @Size(max = 20) String platform) {
    }

    public record PrivacyRequestBody(@NotNull PrivacyRequest.Type type, @Size(max = 1000) String details) {
    }

    @GetMapping
    public AccountService.MeResponse me(@AuthenticationPrincipal AuthUser user) {
        return accounts.me(user);
    }

    @PatchMapping
    public AccountService.MeResponse update(@AuthenticationPrincipal AuthUser user,
                                            @Valid @RequestBody UpdateProfileRequest body) {
        return accounts.updateProfile(user, body.fullName(), body.phoneNumber(), body.photoPath());
    }

    @PutMapping("/notification-preferences")
    public NotificationPreferences preferences(@AuthenticationPrincipal AuthUser user,
                                               @RequestBody NotificationPreferences prefs) {
        return accounts.updatePreferences(user, prefs);
    }

    @PostMapping("/push-tokens")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerPushToken(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody PushTokenRequest body) {
        notifications.registerPushToken(user.uid(), body.token(), body.platform());
    }

    @DeleteMapping("/push-tokens/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removePushToken(@AuthenticationPrincipal AuthUser user, @PathVariable String tokenId) {
        notifications.removePushToken(user.uid(), tokenId);
    }

    /** Encerra a sessão em todos os dispositivos (revoga refresh tokens no Firebase). */
    @PostMapping("/sessions/revoke-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAll(@AuthenticationPrincipal AuthUser user) {
        accounts.revokeAllSessions(user);
    }

    @PostMapping("/privacy-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public PrivacyRequest privacyRequest(@AuthenticationPrincipal AuthUser user,
                                         @Valid @RequestBody PrivacyRequestBody body) {
        return accounts.createPrivacyRequest(user, body.type(), body.details());
    }

    @GetMapping("/privacy-requests")
    public List<PrivacyRequest> privacyRequests(@AuthenticationPrincipal AuthUser user) {
        return accounts.listPrivacyRequests(user);
    }
}
