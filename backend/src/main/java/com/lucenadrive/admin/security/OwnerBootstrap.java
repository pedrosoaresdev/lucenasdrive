package com.lucenadrive.admin.security;

import com.lucenadrive.common.cpf.Cpf;
import com.lucenadrive.common.crypto.FieldEncryptor;
import com.lucenadrive.common.web.Ids;
import com.lucenadrive.audit.AuditLog;
import com.lucenadrive.integration.AuthAdmin;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.security.Role;
import com.lucenadrive.user.IdentityRegistrationService;
import com.lucenadrive.user.NotificationPreferences;
import com.lucenadrive.user.User;
import com.lucenadrive.user.UserPrivate;
import com.lucenadrive.user.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Provisionamento seguro do PROPRIETÁRIO (não existe cadastro público de administradores).
 * Executado uma única vez, por quem opera a infraestrutura, com:
 * LUCENA_BOOTSTRAP_OWNER_EMAIL e LUCENA_BOOTSTRAP_OWNER_CPF (a conta já deve existir no Firebase Auth).
 * Recusa-se a rodar se já houver um OWNER. Após o uso, remova as variáveis.
 * No primeiro acesso ao painel, o proprietário é obrigado a cadastrar o autenticador TOTP.
 */
@Component
@ConditionalOnProperty(prefix = "lucena.bootstrap", name = "owner-email")
public class OwnerBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);

    private final DocStore store;
    private final AuthAdmin authAdmin;
    private final FieldEncryptor encryptor;
    private final Clock clock;
    private final String email;
    private final String cpf;
    private final String fullName;

    public OwnerBootstrap(DocStore store, AuthAdmin authAdmin, FieldEncryptor encryptor, Clock clock,
                          @org.springframework.beans.factory.annotation.Value("${lucena.bootstrap.owner-email}") String email,
                          @org.springframework.beans.factory.annotation.Value("${lucena.bootstrap.owner-cpf:}") String cpf,
                          @org.springframework.beans.factory.annotation.Value("${lucena.bootstrap.owner-name:Proprietário}") String fullName) {
        this.store = store;
        this.authAdmin = authAdmin;
        this.encryptor = encryptor;
        this.clock = clock;
        this.email = email;
        this.cpf = cpf;
        this.fullName = fullName;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    public String bootstrap() {
        if (store.count(Query.of(Collections.USERS).where("roles", Query.Op.ARRAY_CONTAINS, Role.OWNER)) > 0) {
            log.warn("owner_bootstrap_skipped: já existe um proprietário.");
            return null;
        }
        if (!Cpf.isValid(cpf)) {
            throw new IllegalStateException("LUCENA_BOOTSTRAP_OWNER_CPF ausente ou inválido.");
        }
        String uid = authAdmin.uidByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new IllegalStateException("Conta " + email + " não existe no Firebase Auth."));
        String digits = Cpf.digitsOnly(cpf);
        String cpfHash = encryptor.indexHash("cpf:" + digits);
        Instant now = Instant.now(clock);
        store.transaction(tx -> {
            var existingIndex = tx.get(Collections.CPF_INDEX, cpfHash, IdentityRegistrationService.CpfIndex.class);
            if (existingIndex.isPresent() && !existingIndex.get().uid().equals(uid)) {
                throw new IllegalStateException("CPF já vinculado a outra conta.");
            }
            User existing = tx.get(Collections.USERS, uid, User.class).orElse(null);
            boolean hasPrivate = tx.get(Collections.USER_PRIVATE, uid, UserPrivate.class).isPresent();
            var roles = existing == null || existing.roles().isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(existing.roles());
            roles.add(Role.OWNER);
            User owner = existing != null ? existing.withRoles(roles, now)
                    : new User(uid, email.trim().toLowerCase(), fullName, null, null, roles, UserStatus.ACTIVE,
                    Cpf.mask(digits), NotificationPreferences.defaults(), null, null, now, null, now, now);
            tx.set(Collections.USERS, uid, owner);
            if (!hasPrivate) {
                tx.set(Collections.USER_PRIVATE, uid, new UserPrivate(uid, encryptor.encrypt(digits), cpfHash, null, now));
            }
            if (existingIndex.isEmpty()) {
                tx.set(Collections.CPF_INDEX, cpfHash, new IdentityRegistrationService.CpfIndex(uid, now));
            }
            String auditId = Ids.newId();
            tx.set(Collections.ADMIN_AUDIT_LOGS, auditId, new AuditLog(auditId, "bootstrap", List.of("SYSTEM"),
                    "OWNER_BOOTSTRAPPED", "user", uid, null, Map.of("roles", roles.stream().map(Enum::name).toList()),
                    "Provisionamento inicial", null, null, now));
            return null;
        });
        log.warn("owner_bootstrapped uid={} — remova LUCENA_BOOTSTRAP_OWNER_* do ambiente.", uid);
        return uid;
    }
}
