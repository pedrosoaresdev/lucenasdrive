package com.lucenadrive.admin;

import com.lucenadrive.audit.AuditService;
import com.lucenadrive.common.crypto.FieldEncryptor;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.integration.AuthAdmin;
import com.lucenadrive.notification.NotificationService;
import com.lucenadrive.notification.NotificationType;
import com.lucenadrive.passenger.PassengerProfile;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.rating.Rating;
import com.lucenadrive.ride.Ride;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.Role;
import com.lucenadrive.security.SecurityGuards;
import com.lucenadrive.support.SupportTicket;
import com.lucenadrive.user.PrivacyRequest;
import com.lucenadrive.user.User;
import com.lucenadrive.user.UserPrivate;
import com.lucenadrive.user.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AdminUserService {

    public record UserDetail(User user, PassengerProfile passengerProfile, List<Ride> recentRides,
                             List<Rating> ratingsGiven, List<SupportTicket> tickets) {
    }

    private final DocStore store;
    private final AuditService audit;
    private final AuthAdmin authAdmin;
    private final NotificationService notifications;
    private final FieldEncryptor encryptor;
    private final SecurityGuards guards;
    private final Clock clock;

    public AdminUserService(DocStore store, AuditService audit, AuthAdmin authAdmin, NotificationService notifications,
                            FieldEncryptor encryptor, SecurityGuards guards, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.authAdmin = authAdmin;
        this.notifications = notifications;
        this.encryptor = encryptor;
        this.guards = guards;
        this.clock = clock;
    }

    public List<User> list(Role role, UserStatus status, String email, int limit) {
        Query q = Query.of(Collections.USERS);
        if (role != null) {
            q.where("roles", Query.Op.ARRAY_CONTAINS, role);
        }
        if (status != null) {
            q.eq("status", status);
        }
        if (email != null && !email.isBlank()) {
            q.eq("email", email.trim().toLowerCase());
        }
        return store.query(q.orderBy("createdAt", Query.Direction.DESC).limit(Math.min(limit, 200)), User.class);
    }

    public UserDetail detail(String uid) {
        User u = store.get(Collections.USERS, uid, User.class).orElseThrow(() -> ApiException.notFound("Usuário"));
        return new UserDetail(u,
                store.get(Collections.PASSENGER_PROFILES, uid, PassengerProfile.class).orElse(null),
                store.query(Query.of(Collections.RIDES).eq("passengerId", uid)
                        .orderBy("createdAt", Query.Direction.DESC).limit(20), Ride.class),
                store.query(Query.of(Collections.RATINGS).eq("raterId", uid)
                        .orderBy("createdAt", Query.Direction.DESC).limit(20), Rating.class),
                store.query(Query.of(Collections.SUPPORT_TICKETS).eq("userId", uid)
                        .orderBy("createdAt", Query.Direction.DESC).limit(20), SupportTicket.class));
    }

    /** Suspensão preserva todo o histórico (corridas, avaliações, lançamentos). */
    public User setStatus(AuthUser staff, String uid, UserStatus status, String reason) {
        if (reason == null || reason.trim().length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Motivo obrigatório (mín. 10 caracteres).");
        }
        if (staff.uid().equals(uid)) {
            throw ApiException.forbidden("Você não pode alterar o status da própria conta.");
        }
        if (status != UserStatus.ACTIVE && status != UserStatus.SUSPENDED) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Status inválido.");
        }
        User updated = store.transaction(tx -> {
            User u = tx.get(Collections.USERS, uid, User.class).orElseThrow(() -> ApiException.notFound("Usuário"));
            if (u.roles().contains(Role.OWNER)) {
                throw ApiException.forbidden("A conta do proprietário não pode ser suspensa por este fluxo.");
            }
            if (u.roles().stream().anyMatch(Role::isStaff) && !staff.hasRole(Role.OWNER)) {
                throw ApiException.forbidden("Somente o proprietário pode suspender membros da equipe.");
            }
            User next = u.withStatus(status, reason, Instant.now(clock));
            tx.set(Collections.USERS, uid, next);
            audit.recordInTx(tx, staff, status == UserStatus.SUSPENDED ? "USER_SUSPENDED" : "USER_REACTIVATED",
                    "user", uid, Map.of("status", u.status().name()), Map.of("status", status.name()), reason);
            return next;
        });
        if (status == UserStatus.SUSPENDED) {
            authAdmin.revokeSessions(uid);
        }
        notifications.notify(uid, NotificationType.SECURITY_CHANGE,
                status == UserStatus.SUSPENDED ? "Conta suspensa" : "Conta reativada",
                status == UserStatus.SUSPENDED ? "Sua conta foi suspensa. Contate o suporte para mais informações."
                        : "Sua conta foi reativada.", Map.of());
        return updated;
    }

    /** Exibe o CPF completo somente a ADMIN/OWNER, com justificativa e MFA recente. Auditado. */
    public Map<String, String> revealPii(AuthUser staff, String uid, String reason) {
        guards.requireAnyRole(staff, Role.ADMIN, Role.OWNER);
        guards.requireRecentMfa(staff);
        if (reason == null || reason.trim().length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Justificativa obrigatória (mín. 10 caracteres).");
        }
        UserPrivate p = store.get(Collections.USER_PRIVATE, uid, UserPrivate.class)
                .orElseThrow(() -> ApiException.notFound("Dados pessoais"));
        audit.record(staff, "PII_ACCESSED", "user", uid, null, null, reason);
        return Map.of("cpf", encryptor.decrypt(p.cpfEncrypted()), "birthDate", p.birthDate());
    }

    // --------------------------------------------------------------- equipe

    public List<User> staff() {
        return store.query(Query.of(Collections.USERS).where("roles", Query.Op.ARRAY_CONTAINS_ANY, Role.STAFF), User.class);
    }

    /**
     * Concede/revoga papéis de equipe. Regras: ninguém altera os próprios papéis; OWNER nunca é
     * concedido por API (somente provisionamento seguro); ADMIN só gerencia SUPPORT; FINANCE e ADMIN
     * só pelo OWNER. Exige MFA recente.
     */
    public User changeStaffRole(AuthUser actor, String email, Role role, boolean grant, String reason) {
        guards.requireRecentMfa(actor);
        if (role == Role.OWNER || !role.isStaff()) {
            throw ApiException.forbidden("Este papel não pode ser concedido por aqui.");
        }
        if (!actor.hasRole(Role.OWNER) && !(actor.hasRole(Role.ADMIN) && role == Role.SUPPORT)) {
            throw ApiException.forbidden("Permissão insuficiente para gerenciar o papel " + role + ".");
        }
        if (reason == null || reason.trim().length() < 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Motivo obrigatório (mín. 10 caracteres).");
        }
        String uid = authAdmin.uidByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Nenhuma conta com este e-mail. A pessoa deve criar a conta antes."));
        if (uid.equals(actor.uid())) {
            throw ApiException.forbidden("Você não pode alterar seus próprios privilégios.");
        }
        User updated = store.transaction(tx -> {
            Instant now = Instant.now(clock);
            User u = tx.get(Collections.USERS, uid, User.class).orElse(new User(uid, email.trim().toLowerCase(), null, null,
                    null, EnumSet.noneOf(Role.class), UserStatus.ACTIVE, null,
                    com.lucenadrive.user.NotificationPreferences.defaults(), null, null, null, null, now, now));
            if (u.roles().contains(Role.OWNER)) {
                throw ApiException.forbidden("Papéis do proprietário não podem ser alterados.");
            }
            Set<Role> roles = u.roles().isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(u.roles());
            if (grant) {
                roles.add(role);
            } else {
                roles.remove(role);
            }
            User next = u.withRoles(roles, now);
            tx.set(Collections.USERS, uid, next);
            audit.recordInTx(tx, actor, grant ? "STAFF_ROLE_GRANTED" : "STAFF_ROLE_REVOKED", "user", uid,
                    Map.of("roles", u.roles().stream().map(Enum::name).sorted().toList()),
                    Map.of("roles", roles.stream().map(Enum::name).sorted().toList()), reason);
            return next;
        });
        if (!grant) {
            authAdmin.revokeSessions(uid);
        }
        notifications.notify(uid, NotificationType.SECURITY_CHANGE, "Permissões alteradas",
                "Suas permissões administrativas foram alteradas.", Map.of());
        return updated;
    }

    // --------------------------------------------------------------- privacidade

    public List<PrivacyRequest> privacyRequests(PrivacyRequest.Status status) {
        Query q = Query.of(Collections.PRIVACY_REQUESTS);
        if (status != null) {
            q.eq("status", status);
        }
        return store.query(q.orderBy("createdAt", Query.Direction.DESC).limit(200), PrivacyRequest.class);
    }

    public PrivacyRequest updatePrivacyRequest(AuthUser staff, String id, PrivacyRequest.Status status, String note) {
        return store.transaction(tx -> {
            PrivacyRequest r = tx.get(Collections.PRIVACY_REQUESTS, id, PrivacyRequest.class)
                    .orElseThrow(() -> ApiException.notFound("Solicitação"));
            PrivacyRequest next = new PrivacyRequest(r.id(), r.userId(), r.type(), status, r.details(), note, staff.uid(),
                    r.createdAt(), Instant.now(clock));
            tx.set(Collections.PRIVACY_REQUESTS, id, next);
            audit.recordInTx(tx, staff, "PRIVACY_REQUEST_UPDATED", "privacyRequest", id,
                    Map.of("status", r.status().name()), Map.of("status", status.name()), note);
            return next;
        });
    }
}
