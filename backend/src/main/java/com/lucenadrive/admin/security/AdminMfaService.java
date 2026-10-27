package com.lucenadrive.admin.security;

import com.lucenadrive.common.crypto.FieldEncryptor;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.common.web.Ids;
import com.lucenadrive.notification.NotificationService;
import com.lucenadrive.notification.NotificationType;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.RequestContext;
import com.lucenadrive.security.SecurityGuards;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Segundo fator administrativo (TOTP + códigos de recuperação de uso único), com bloqueio após
 * tentativas falhas, proteção contra reutilização de código, alerta de novo dispositivo e registro
 * de eventos de segurança.
 */
@Service
public class AdminMfaService {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final int RECOVERY_CODES = 10;

    public record Status(boolean enrolled, boolean locked, Instant lockedUntil, int recoveryCodesRemaining) {
    }

    public record Enrollment(String secret, String otpauthUri) {
    }

    public record VerifyResult(String sessionToken, Instant expiresAt, boolean newDevice, boolean usedRecoveryCode) {
    }

    private final DocStore store;
    private final FieldEncryptor encryptor;
    private final AdminSessionService sessions;
    private final NotificationService notifications;
    private final SecurityGuards guards;
    private final Clock clock;

    public AdminMfaService(DocStore store, FieldEncryptor encryptor, AdminSessionService sessions,
                           NotificationService notifications, SecurityGuards guards, Clock clock) {
        this.store = store;
        this.encryptor = encryptor;
        this.sessions = sessions;
        this.notifications = notifications;
        this.guards = guards;
        this.clock = clock;
    }

    private AdminSecurity load(String uid) {
        return store.get(Collections.ADMIN_SECURITY, uid, AdminSecurity.class)
                .orElse(AdminSecurity.empty(uid, Instant.now(clock)));
    }

    public Status status(AuthUser user) {
        AdminSecurity s = load(user.uid());
        Instant now = Instant.now(clock);
        boolean locked = s.lockedUntil() != null && s.lockedUntil().isAfter(now);
        return new Status(s.totpEnabled(), locked, locked ? s.lockedUntil() : null,
                s.recoveryCodeHashes() == null ? 0 : s.recoveryCodeHashes().size());
    }

    /** Inicia o cadastro do autenticador. Exige login recente; não substitui um fator já ativo. */
    public Enrollment beginEnrollment(AuthUser user) {
        guards.requireRecentLogin(user);
        AdminSecurity s = load(user.uid());
        if (s.totpEnabled()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Autenticador já cadastrado. A troca exige o procedimento de recuperação com outro administrador.");
        }
        String secret = Totp.newSecret();
        store.set(Collections.ADMIN_SECURITY, user.uid(), new AdminSecurity(user.uid(), null, encryptor.encrypt(secret),
                false, null, List.of(), 0, null, -1, s.knownDeviceHashes(), Instant.now(clock)));
        return new Enrollment(secret, Totp.otpauthUri("Lucena Drive Admin", user.email(), secret));
    }

    /** Confirma o cadastro com um código válido e devolve os códigos de recuperação (exibidos uma vez). */
    public List<String> confirmEnrollment(AuthUser user, String code) {
        AdminSecurity s = load(user.uid());
        if (s.totpEnabled() || s.pendingSecretEncrypted() == null) {
            throw new ApiException(ErrorCode.CONFLICT, "Nenhum cadastro de autenticador pendente.");
        }
        String secret = decryptSecret(s.pendingSecretEncrypted());
        long step = Totp.verify(secret, code, clock.instant().getEpochSecond());
        if (step < 0) {
            throw new ApiException(ErrorCode.MFA_INVALID_CODE, "Código inválido. Verifique o horário do celular.");
        }
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String c = Ids.random(5) + "-" + Ids.random(5);
            codes.add(c);
            hashes.add(encryptor.indexHash("recovery:" + user.uid() + ":" + c));
        }
        Instant now = Instant.now(clock);
        store.set(Collections.ADMIN_SECURITY, user.uid(), new AdminSecurity(user.uid(), s.pendingSecretEncrypted(), null,
                true, now, hashes, 0, null, step, s.knownDeviceHashes(), now));
        event(user.uid(), "TOTP_ENROLLED", Map.of());
        notifications.notify(user.uid(), NotificationType.SECURITY_CHANGE, "Autenticador cadastrado",
                "O segundo fator da sua conta administrativa foi ativado.", Map.of());
        return codes;
    }

    /** Valida o segundo fator e abre uma sessão administrativa. */
    public VerifyResult verify(AuthUser user, String code, String recoveryCode) {
        boolean usedRecovery = checkFactor(user, code, recoveryCode);
        AdminSessionService.IssuedSession issued = sessions.create(user.uid());
        boolean newDevice = registerDevice(user.uid());
        event(user.uid(), "MFA_SUCCESS", Map.of("sessionId", issued.session().id(), "recovery", usedRecovery));
        if (newDevice) {
            event(user.uid(), "NEW_DEVICE", Map.of("sessionId", issued.session().id()));
            notifications.notify(user.uid(), NotificationType.SECURITY_CHANGE, "Novo acesso administrativo",
                    "Um novo dispositivo acessou o painel. Se não foi você, revogue as sessões imediatamente.", Map.of());
        }
        return new VerifyResult(issued.token(), issued.session().expiresAt(), newDevice, usedRecovery);
    }

    /** Reconfirma o segundo fator na sessão atual (operações críticas). */
    public Instant stepUp(AuthUser user, String code) {
        if (user.adminSessionId() == null) {
            throw new ApiException(ErrorCode.MFA_REQUIRED, "Sessão administrativa inexistente.");
        }
        checkFactor(user, code, null);
        sessions.stepUp(user.adminSessionId());
        event(user.uid(), "STEP_UP", Map.of("sessionId", user.adminSessionId()));
        return Instant.now(clock);
    }

    public List<String> regenerateRecoveryCodes(AuthUser user) {
        guards.requireRecentMfa(user);
        AdminSecurity s = load(user.uid());
        List<String> codes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String c = Ids.random(5) + "-" + Ids.random(5);
            codes.add(c);
            hashes.add(encryptor.indexHash("recovery:" + user.uid() + ":" + c));
        }
        store.set(Collections.ADMIN_SECURITY, user.uid(), new AdminSecurity(s.id(), s.totpSecretEncrypted(), null,
                s.totpEnabled(), s.enrolledAt(), hashes, 0, null, s.lastUsedTimeStep(), s.knownDeviceHashes(),
                Instant.now(clock)));
        event(user.uid(), "RECOVERY_CODES_REGENERATED", Map.of());
        return codes;
    }

    /** Valida TOTP ou código de recuperação, aplicando bloqueio e anti-replay. Retorna true se usou recuperação. */
    boolean checkFactor(AuthUser user, String code, String recoveryCode) {
        // A transação grava a falha (contador/bloqueio) e retorna null; a exceção é lançada depois
        // do commit para que a tentativa inválida fique registrada.
        Boolean result = store.transaction(tx -> {
            Instant now = Instant.now(clock);
            AdminSecurity s = tx.get(Collections.ADMIN_SECURITY, user.uid(), AdminSecurity.class)
                    .orElseThrow(() -> new ApiException(ErrorCode.MFA_REQUIRED, "Cadastre o aplicativo autenticador."));
            if (!s.totpEnabled()) {
                throw new ApiException(ErrorCode.MFA_REQUIRED, "Cadastre o aplicativo autenticador.");
            }
            if (s.lockedUntil() != null && s.lockedUntil().isAfter(now)) {
                throw new ApiException(ErrorCode.MFA_LOCKED, "Muitas tentativas. Tente novamente mais tarde.",
                        Map.of("lockedUntil", s.lockedUntil().toString()));
            }
            boolean ok = false;
            boolean recovery = false;
            long step = s.lastUsedTimeStep();
            List<String> hashes = new ArrayList<>(s.recoveryCodeHashes() == null ? List.of() : s.recoveryCodeHashes());
            if (recoveryCode != null && !recoveryCode.isBlank()) {
                String h = encryptor.indexHash("recovery:" + user.uid() + ":" + recoveryCode.trim().toLowerCase());
                if (hashes.remove(h)) {
                    ok = true;
                    recovery = true;
                }
            } else {
                long matched = Totp.verify(decryptSecret(s.totpSecretEncrypted()), code, now.getEpochSecond());
                if (matched >= 0 && matched > s.lastUsedTimeStep()) {
                    ok = true;
                    step = matched;
                }
            }
            if (!ok) {
                int failures = s.failedAttempts() + 1;
                Instant lock = failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null;
                tx.set(Collections.ADMIN_SECURITY, s.id(), new AdminSecurity(s.id(), s.totpSecretEncrypted(), null, true,
                        s.enrolledAt(), hashes, lock != null ? 0 : failures, lock, s.lastUsedTimeStep(),
                        s.knownDeviceHashes(), now));
                String eventId = Ids.newId();
                tx.set(Collections.SECURITY_EVENTS, eventId, new SecurityEvent(eventId, user.uid(),
                        lock != null ? "MFA_LOCKED" : "MFA_FAILURE", RequestContext.clientIp(),
                        RequestContext.userAgent(), Map.of("failures", failures), now));
                return (Boolean) null;
            }
            tx.set(Collections.ADMIN_SECURITY, s.id(), new AdminSecurity(s.id(), s.totpSecretEncrypted(), null, true,
                    s.enrolledAt(), hashes, 0, null, step, s.knownDeviceHashes(), now));
            return recovery;
        });
        if (result == null) {
            throw new ApiException(ErrorCode.MFA_INVALID_CODE, "Código inválido.");
        }
        return result;
    }

    /**
     * O segredo só se torna ilegível se a chave LUCENA_DATA_ENCRYPTION_KEY mudar (ex.: chave efêmera em dev
     * após reinício). Nesse caso exige-se o procedimento de recuperação, sem expor detalhes.
     */
    private String decryptSecret(String encrypted) {
        try {
            return encryptor.decrypt(encrypted);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Não foi possível validar o autenticador (chave de criptografia do servidor alterada). "
                            + "Siga o procedimento de recuperação de acesso administrativo.");
        }
    }

    private boolean registerDevice(String uid) {
        String ua = RequestContext.userAgent();
        String deviceHash = encryptor.indexHash("device:" + uid + ":" + (ua == null ? "" : ua));
        return store.transaction(tx -> {
            AdminSecurity s = tx.get(Collections.ADMIN_SECURITY, uid, AdminSecurity.class).orElse(null);
            if (s == null) {
                return false;
            }
            List<String> devices = new ArrayList<>(s.knownDeviceHashes() == null ? List.of() : s.knownDeviceHashes());
            if (devices.contains(deviceHash)) {
                return false;
            }
            devices.add(deviceHash);
            if (devices.size() > 20) {
                devices.remove(0);
            }
            tx.set(Collections.ADMIN_SECURITY, uid, new AdminSecurity(s.id(), s.totpSecretEncrypted(),
                    s.pendingSecretEncrypted(), s.totpEnabled(), s.enrolledAt(), s.recoveryCodeHashes(),
                    s.failedAttempts(), s.lockedUntil(), s.lastUsedTimeStep(), devices, Instant.now(clock)));
            return true;
        });
    }

    public void event(String uid, String type, Map<String, Object> data) {
        SecurityEvent e = new SecurityEvent(Ids.newId(), uid, type, RequestContext.clientIp(), RequestContext.userAgent(),
                data, Instant.now(clock));
        store.set(Collections.SECURITY_EVENTS, e.id(), e);
    }
}
