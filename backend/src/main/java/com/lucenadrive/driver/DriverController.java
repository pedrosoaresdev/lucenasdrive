package com.lucenadrive.driver;

import com.lucenadrive.common.cpf.ValidCpf;
import com.lucenadrive.fare.VehicleCategory;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.user.IdentityRegistrationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/drivers")
@Tag(name = "Motoristas", description = "Cadastro, documentos, veículos e disponibilidade")
public class DriverController {

    private final DriverService service;

    public DriverController(DriverService service) {
        this.service = service;
    }

    public record RegisterDriverRequest(
            @NotBlank @Size(min = 5, max = 120) String fullName,
            @NotBlank @ValidCpf String cpf,
            @NotNull LocalDate birthDate,
            @NotBlank @Pattern(regexp = "^\\+55\\d{10,11}$", message = "Telefone no formato +55DDDNÚMERO") String phoneNumber,
            @AssertTrue(message = "Aceite os termos de uso") boolean acceptTerms,
            @AssertTrue(message = "Declare ciência da política de privacidade") boolean acceptPrivacy,
            @NotBlank String termsVersion,
            @NotBlank String privacyVersion,
            @NotBlank @Size(min = 11, max = 14) String cnhNumber,
            @NotBlank @Pattern(regexp = "^(B|C|D|E|AB|AC|AD|AE)$") String cnhCategory,
            @NotNull LocalDate cnhExpiresOn,
            boolean cnhEar,
            @NotBlank String cityId) {
    }

    public record VehicleRequest(
            @NotBlank @Size(max = 40) String make,
            @NotBlank @Size(max = 60) String model,
            @Min(1990) @Max(2100) int year,
            @NotBlank @Size(max = 30) String color,
            @NotBlank @Size(max = 10) String plate,
            @NotNull VehicleCategory category,
            LocalDate crlvExpiresOn) {
    }

    public record DocumentRequest(@NotNull DocumentType type, @NotBlank @Size(max = 500) String storagePath,
                                  LocalDate expiresOn, String vehicleId) {
    }

    public record OnlineRequest(@DecimalMin("-90") @DecimalMax("90") double lat,
                                @DecimalMin("-180") @DecimalMax("180") double lng) {
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public DriverProfile register(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody RegisterDriverRequest r) {
        var identity = new IdentityRegistrationService.IdentityData(r.fullName(), r.cpf(), r.birthDate(),
                r.phoneNumber(), r.acceptTerms(), r.acceptPrivacy(), r.termsVersion(), r.privacyVersion());
        return service.register(user, new DriverService.DriverRegistration(identity, r.cnhNumber(), r.cnhCategory(),
                r.cnhExpiresOn(), r.cnhEar(), r.cityId()));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('DRIVER')")
    public DriverService.DriverOverview me(@AuthenticationPrincipal AuthUser user) {
        return service.overview(user.uid());
    }

    @PostMapping("/me/vehicles")
    @PreAuthorize("hasRole('DRIVER')")
    @ResponseStatus(HttpStatus.CREATED)
    public Vehicle addVehicle(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody VehicleRequest r) {
        return service.addVehicle(user, new DriverService.VehicleInput(r.make(), r.model(), r.year(), r.color(),
                r.plate(), r.category(), r.crlvExpiresOn()));
    }

    @GetMapping("/me/vehicles")
    @PreAuthorize("hasRole('DRIVER')")
    public List<Vehicle> vehicles(@AuthenticationPrincipal AuthUser user) {
        return service.vehicles(user.uid());
    }

    @PostMapping("/me/vehicles/{vehicleId}/activate")
    @PreAuthorize("hasRole('DRIVER')")
    public DriverProfile activateVehicle(@AuthenticationPrincipal AuthUser user, @PathVariable String vehicleId) {
        return service.setActiveVehicle(user, vehicleId);
    }

    @PostMapping("/me/documents")
    @PreAuthorize("hasRole('DRIVER')")
    public DriverProfile attachDocument(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody DocumentRequest r) {
        return service.attachDocument(user, new DriverService.DocumentInput(r.type(), r.storagePath(), r.expiresOn(),
                r.vehicleId()));
    }

    @PostMapping("/me/submit")
    @PreAuthorize("hasRole('DRIVER')")
    public DriverProfile submit(@AuthenticationPrincipal AuthUser user) {
        return service.submitForReview(user);
    }

    @PostMapping("/me/online")
    @PreAuthorize("hasRole('DRIVER')")
    public DriverLocation online(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody OnlineRequest r) {
        return service.goOnline(user, r.lat(), r.lng());
    }

    @PostMapping("/me/offline")
    @PreAuthorize("hasRole('DRIVER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void offline(@AuthenticationPrincipal AuthUser user) {
        service.goOffline(user);
    }
}
