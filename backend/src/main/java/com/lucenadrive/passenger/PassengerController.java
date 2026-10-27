package com.lucenadrive.passenger;

import com.lucenadrive.common.cpf.ValidCpf;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.user.IdentityRegistrationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/passengers")
@Tag(name = "Passageiros")
public class PassengerController {

    private final PassengerService service;

    public PassengerController(PassengerService service) {
        this.service = service;
    }

    /** A senha não trafega aqui: a conta é criada no Firebase Authentication pelo cliente. */
    public record RegisterPassengerRequest(
            @NotBlank @Size(min = 5, max = 120) String fullName,
            @NotBlank @ValidCpf String cpf,
            @NotNull LocalDate birthDate,
            @NotBlank @Pattern(regexp = "^\\+55\\d{10,11}$", message = "Telefone no formato +55DDDNÚMERO") String phoneNumber,
            @AssertTrue(message = "Aceite os termos de uso") boolean acceptTerms,
            @AssertTrue(message = "Declare ciência da política de privacidade") boolean acceptPrivacy,
            @NotBlank String termsVersion,
            @NotBlank String privacyVersion) {
    }

    public record FavoritesRequest(@NotNull @Size(max = 10) @Valid List<PassengerProfile.FavoritePlace> favorites) {
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public PassengerProfile register(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody RegisterPassengerRequest r) {
        return service.register(user, new IdentityRegistrationService.IdentityData(r.fullName(), r.cpf(), r.birthDate(),
                r.phoneNumber(), r.acceptTerms(), r.acceptPrivacy(), r.termsVersion(), r.privacyVersion()));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('PASSENGER')")
    public PassengerProfile me(@AuthenticationPrincipal AuthUser user) {
        return service.profile(user.uid());
    }

    @PutMapping("/me/favorites")
    @PreAuthorize("hasRole('PASSENGER')")
    public PassengerProfile favorites(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody FavoritesRequest r) {
        return service.updateFavorites(user, r.favorites());
    }
}
