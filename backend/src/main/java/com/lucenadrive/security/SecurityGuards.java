package com.lucenadrive.security;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.config.LucenaProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.stereotype.Component;

/** Verificações de autorização reutilizadas pelos serviços (defesa em profundidade). */
@Component
public class SecurityGuards {

    private final Clock clock;
    private final LucenaProperties props;

    public SecurityGuards(Clock clock, LucenaProperties props) {
        this.clock = clock;
        this.props = props;
    }

    public void requireAnyRole(AuthUser user, Role... roles) {
        if (Arrays.stream(roles).noneMatch(user::hasRole)) {
            throw ApiException.forbidden("Permissão insuficiente para esta operação.");
        }
    }

    public void requireVerifiedEmail(AuthUser user) {
        if (props.security().requireVerifiedEmail() && !user.emailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED, "Confirme seu e-mail para continuar.");
        }
    }

    /** Operações administrativas críticas exigem MFA validado há pouco tempo (step-up). */
    public void requireRecentMfa(AuthUser user) {
        Duration window = props.security().stepUpWindow();
        if (user.mfaVerifiedAt() == null || user.mfaVerifiedAt().plus(window).isBefore(Instant.now(clock))) {
            throw new ApiException(ErrorCode.MFA_REQUIRED,
                    "Confirme novamente o segundo fator para executar esta operação.");
        }
    }

    /** Operações sensíveis de usuários comuns exigem login recente (reautenticação no Firebase). */
    public void requireRecentLogin(AuthUser user) {
        Duration window = props.security().reauthWindow();
        if (user.authTime() == null || user.authTime().plus(window).isBefore(Instant.now(clock))) {
            throw new ApiException(ErrorCode.REAUTHENTICATION_REQUIRED, "Entre novamente para confirmar sua identidade.");
        }
    }
}
