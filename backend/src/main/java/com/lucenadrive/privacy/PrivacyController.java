package com.lucenadrive.privacy;

import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Privacidade (LGPD)")
public class PrivacyController {

    private final PrivacyService privacy;

    public PrivacyController(PrivacyService privacy) {
        this.privacy = privacy;
    }

    public record AnonymizeRequest(@NotBlank @Size(min = 10, max = 500) String reason) {
    }

    @GetMapping("/api/v1/me/data-export")
    @Operation(summary = "Exporta os dados do titular (exige login recente)")
    public ResponseEntity<Map<String, Object>> export(@AuthenticationPrincipal AuthUser user) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lucena-drive-meus-dados.json\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(privacy.export(user));
    }

    @PostMapping("/api/v1/admin/privacy-requests/{id}/anonymize")
    @PreAuthorize("hasAnyRole('ADMIN','OWNER')")
    @Operation(summary = "Executa a anonimização de uma solicitação de exclusão (MFA recente + justificativa)")
    public PrivacyService.AnonymizationResult anonymize(@AuthenticationPrincipal AuthUser staff, @PathVariable String id,
                                                        @Valid @RequestBody AnonymizeRequest body) {
        return privacy.anonymize(staff, id, body.reason());
    }
}
