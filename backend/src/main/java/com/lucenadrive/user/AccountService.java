package com.lucenadrive.user;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.common.web.Ids;
import com.lucenadrive.driver.DriverProfile;
import com.lucenadrive.integration.AuthAdmin;
import com.lucenadrive.notification.NotificationService;
import com.lucenadrive.notification.NotificationType;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    public record MeResponse(String uid, String email, boolean emailVerified, String phoneNumber,
                             boolean phoneVerified, boolean registered, String fullName, String photoUrl,
                             Set<Role> roles, UserStatus status, String cpfMasked,
                             NotificationPreferences notificationPreferences, String driverStatus) {
    }

    private final UserRepository users;
    private final DocStore store;
    private final AuthAdmin authAdmin;
    private final NotificationService notifications;
    private final Clock clock;

    public AccountService(UserRepository users, DocStore store, AuthAdmin authAdmin,
                          NotificationService notifications, Clock clock) {
        this.users = users;
        this.store = store;
        this.authAdmin = authAdmin;
        this.notifications = notifications;
        this.clock = clock;
    }

    public MeResponse me(AuthUser auth) {
        var user = users.findById(auth.uid());
        if (user.isEmpty()) {
            return new MeResponse(auth.uid(), auth.email(), auth.emailVerified(), auth.phoneNumber(),
                    auth.phoneNumber() != null, false, null, null, Set.of(), UserStatus.ACTIVE, null,
                    NotificationPreferences.defaults(), null);
        }
        User u = user.get();
        String driverStatus = u.roles().contains(Role.DRIVER)
                ? store.get(Collections.DRIVER_PROFILES, u.id(), DriverProfile.class)
                    .map(d -> d.status().name()).orElse(null)
                : null;
        return new MeResponse(u.id(), u.email(), auth.emailVerified(), u.phoneNumber(),
                auth.phoneNumber() != null && auth.phoneNumber().equals(u.phoneNumber()), true,
                u.fullName(), u.photoUrl(), u.roles(), u.status(), u.cpfMasked(),
                u.notificationPreferences(), driverStatus);
    }

    public MeResponse updateProfile(AuthUser auth, String fullName, String phone, String photoPath) {
        User u = users.require(auth.uid());
        if (photoPath != null && !photoPath.isBlank() && !photoPath.startsWith("profilePhotos/" + auth.uid() + "/")) {
            throw ApiException.forbidden("Caminho de foto inválido.");
        }
        users.save(u.withContact(fullName.trim(), phone,
                photoPath == null || photoPath.isBlank() ? u.photoUrl() : photoPath, Instant.now(clock)));
        return me(auth);
    }

    public NotificationPreferences updatePreferences(AuthUser auth, NotificationPreferences prefs) {
        User u = users.require(auth.uid());
        users.save(u.withPreferences(prefs, Instant.now(clock)));
        return prefs;
    }

    public void revokeAllSessions(AuthUser auth) {
        authAdmin.revokeSessions(auth.uid());
        notifications.notify(auth.uid(), NotificationType.SECURITY_CHANGE, "Sessões encerradas",
                "Sua conta foi desconectada de todos os dispositivos.", Map.of());
    }

    public PrivacyRequest createPrivacyRequest(AuthUser auth, PrivacyRequest.Type type, String details) {
        User u = users.require(auth.uid());
        Instant now = Instant.now(clock);
        PrivacyRequest req = new PrivacyRequest(Ids.newId(), u.id(), type, PrivacyRequest.Status.OPEN,
                details, null, null, now, now);
        store.set(Collections.PRIVACY_REQUESTS, req.id(), req);
        if (type == PrivacyRequest.Type.ACCOUNT_DELETION) {
            users.save(u.withStatus(UserStatus.PENDING_DELETION, "Exclusão solicitada pelo titular", now));
        }
        return req;
    }

    public List<PrivacyRequest> listPrivacyRequests(AuthUser auth) {
        return store.query(Query.of(Collections.PRIVACY_REQUESTS).eq("userId", auth.uid())
                .orderBy("createdAt", Query.Direction.DESC).limit(50), PrivacyRequest.class);
    }

    public static void ensureActive(User u) {
        if (u.status() != UserStatus.ACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "Conta suspensa ou indisponível.");
        }
    }
}
