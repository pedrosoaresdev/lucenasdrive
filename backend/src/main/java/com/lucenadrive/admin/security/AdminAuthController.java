package com.lucenadrive.admin.security;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.security.AuthUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Login administrativo em duas etapas: Firebase (e-mail/senha) + TOTP, gerando sessão revogável. */
@RestController
@RequestMapping("/api/v1/admin/auth")
@Tag(name = "Admin · Autenticação reforçada")
public class AdminAuthController {

    private final AdminMfaService mfa;
    private final AdminSessionService sessions;

    public AdminAuthController(AdminMfaService mfa, AdminSessionService sessions) {
        this.mfa = mfa;
        this.sessions = sessions;
    }

    public record CodeRequest(@Pattern(regexp = "^\\d{6}$") String code) {
    }

    public record VerifyRequest(@Pattern(regexp = "^\\d{6}$") String code, @Size(max = 20) String recoveryCode) {
    }

    public record StepUpResponse(Instant mfaVerifiedAt) {
    }

    public record RecoveryCodesResponse(List<String> recoveryCodes) {
    }

    @GetMapping("/status")
    public AdminMfaService.Status status(@AuthenticationPrincipal AuthUser user) {
        return mfa.status(user);
    }

    @PostMapping("/totp/enroll")
    public AdminMfaService.Enrollment enroll(@AuthenticationPrincipal AuthUser user) {
        return mfa.beginEnrollment(user);
    }

    @PostMapping("/totp/confirm")
    public RecoveryCodesResponse confirm(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CodeRequest body) {
        return new RecoveryCodesResponse(mfa.confirmEnrollment(user, body.code()));
    }

    @PostMapping("/verify")
    public AdminMfaService.VerifyResult verify(@AuthenticationPrincipal AuthUser user,
                                               @Valid @RequestBody VerifyRequest body) {
        if ((body.code() == null) == (body.recoveryCode() == null || body.recoveryCode().isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Informe o código do autenticador OU um código de recuperação.");
        }
        return mfa.verify(user, body.code(), body.recoveryCode());
    }

    @PostMapping("/step-up")
    public StepUpResponse stepUp(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CodeRequest body) {
        if (body.code() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Código obrigatório.");
        }
        return new StepUpResponse(mfa.stepUp(user, body.code()));
    }

    @PostMapping("/recovery-codes")
    public RecoveryCodesResponse regenerate(@AuthenticationPrincipal AuthUser user) {
        return new RecoveryCodesResponse(mfa.regenerateRecoveryCodes(user));
    }

    @GetMapping("/sessions")
    public List<AdminSession> sessions(@AuthenticationPrincipal AuthUser user) {
        requireSession(user);
        return sessions.listActive(user.uid());
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AuthUser user, @PathVariable String sessionId) {
        requireSession(user);
        if (!sessions.revoke(sessionId, user.uid(), user.uid())) {
            throw ApiException.notFound("Sessão");
        }
        mfa.event(user.uid(), "SESSION_REVOKED", java.util.Map.of("sessionId", sessionId));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal AuthUser user) {
        if (user.adminSessionId() != null) {
            sessions.revoke(user.adminSessionId(), user.uid(), user.uid());
        }
    }

    private static void requireSession(AuthUser user) {
        if (!user.mfaVerified()) {
            throw new ApiException(ErrorCode.MFA_REQUIRED, "Segundo fator necessário.");
        }
    }
}
