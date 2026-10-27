package com.lucenadrive.user;

import com.lucenadrive.security.Role;
import java.time.Instant;
import java.util.Set;

/**
 * users/{uid}. Legível pelo próprio usuário (regras do Firestore); gravado somente pelo backend.
 * Não contém CPF completo nem data de nascimento (ficam em userPrivate, criptografados).
 */
public record User(
        String id,
        String email,
        String fullName,
        String phoneNumber,
        String photoUrl,
        Set<Role> roles,
        UserStatus status,
        String cpfMasked,
        NotificationPreferences notificationPreferences,
        String termsVersion,
        String privacyVersion,
        Instant consentAt,
        String statusReason,
        Instant createdAt,
        Instant updatedAt) {

    public String firstName() {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        return fullName.trim().split("\\s+")[0];
    }

    /** Nome de exibição para a outra parte da corrida: primeiro nome + inicial do sobrenome. */
    public String publicName() {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        String[] parts = fullName.trim().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    public User withRoles(Set<Role> newRoles, Instant now) {
        return new User(id, email, fullName, phoneNumber, photoUrl, newRoles, status, cpfMasked,
                notificationPreferences, termsVersion, privacyVersion, consentAt, statusReason, createdAt, now);
    }

    public User withStatus(UserStatus newStatus, String reason, Instant now) {
        return new User(id, email, fullName, phoneNumber, photoUrl, roles, newStatus, cpfMasked,
                notificationPreferences, termsVersion, privacyVersion, consentAt, reason, createdAt, now);
    }

    public User withContact(String newName, String newPhone, String newPhotoUrl, Instant now) {
        return new User(id, email, newName, newPhone, newPhotoUrl, roles, status, cpfMasked,
                notificationPreferences, termsVersion, privacyVersion, consentAt, statusReason, createdAt, now);
    }

    public User withPreferences(NotificationPreferences prefs, Instant now) {
        return new User(id, email, fullName, phoneNumber, photoUrl, roles, status, cpfMasked,
                prefs, termsVersion, privacyVersion, consentAt, statusReason, createdAt, now);
    }
}
