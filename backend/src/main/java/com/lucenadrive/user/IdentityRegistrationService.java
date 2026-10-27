package com.lucenadrive.user;

import com.lucenadrive.common.cpf.Cpf;
import com.lucenadrive.common.crypto.FieldEncryptor;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.config.LucenaProperties;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.Tx;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Cria ou complementa a identidade (users + userPrivate + índice de CPF) dentro de uma transação.
 * A senha nunca passa pelo backend: é definida e armazenada (com hash) pelo Firebase Authentication.
 */
@Service
public class IdentityRegistrationService {

    public static final ZoneId BR = ZoneId.of("America/Sao_Paulo");

    public record IdentityData(String fullName, String cpf, LocalDate birthDate, String phoneNumber,
                               boolean acceptTerms, boolean acceptPrivacy, String termsVersion,
                               String privacyVersion) {
    }

    private final FieldEncryptor encryptor;
    private final LucenaProperties props;
    private final Clock clock;

    public IdentityRegistrationService(FieldEncryptor encryptor, LucenaProperties props, Clock clock) {
        this.encryptor = encryptor;
        this.props = props;
        this.clock = clock;
    }

    public static int ageOn(LocalDate birthDate, LocalDate today) {
        return Period.between(birthDate, today).getYears();
    }

    public int minimumAge(Role role) {
        return role == Role.DRIVER ? props.eligibility().driverMinAge() : props.eligibility().passengerMinAge();
    }

    /**
     * Executa dentro de uma transação. Leituras primeiro; escritas depois.
     * @return usuário resultante (novo ou com papel adicionado)
     */
    public User registerInTx(Tx tx, AuthUser auth, IdentityData data, Role role) {
        if (!data.acceptTerms() || !data.acceptPrivacy()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "É necessário aceitar os termos de uso e declarar ciência da política de privacidade.");
        }
        if (!Cpf.isValid(data.cpf())) {
            throw new ApiException(ErrorCode.INVALID_CPF, "CPF inválido (formato ou dígitos verificadores).");
        }
        LocalDate today = LocalDate.now(clock.withZone(BR));
        if (data.birthDate().isAfter(today)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Data de nascimento inválida.");
        }
        int age = ageOn(data.birthDate(), today);
        int min = minimumAge(role);
        if (age < min) {
            throw new ApiException(ErrorCode.UNDERAGE, "Idade mínima para este cadastro: " + min + " anos.",
                    Map.of("minimumAge", min));
        }
        String cpfDigits = Cpf.digitsOnly(data.cpf());
        String cpfHash = encryptor.indexHash("cpf:" + cpfDigits);

        var existingUser = tx.get(Collections.USERS, auth.uid(), User.class);
        var cpfOwner = tx.get(Collections.CPF_INDEX, cpfHash, CpfIndex.class);
        var existingPrivate = tx.get(Collections.USER_PRIVATE, auth.uid(), UserPrivate.class);

        if (cpfOwner.isPresent() && !cpfOwner.get().uid().equals(auth.uid())) {
            throw new ApiException(ErrorCode.CPF_ALREADY_REGISTERED, "Este CPF já está vinculado a outra conta.");
        }
        Instant now = Instant.now(clock);
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            if (user.status() != UserStatus.ACTIVE) {
                throw new ApiException(ErrorCode.ACCOUNT_SUSPENDED, "Conta indisponível para novos cadastros.");
            }
            if (existingPrivate.isPresent() && !existingPrivate.get().cpfHash().equals(cpfHash)) {
                throw new ApiException(ErrorCode.CONFLICT, "O CPF informado não corresponde ao cadastro existente.");
            }
            if (user.roles().contains(role)) {
                throw new ApiException(ErrorCode.ALREADY_REGISTERED, "Cadastro já realizado.");
            }
            Set<Role> roles = EnumSet.copyOf(user.roles().isEmpty() ? EnumSet.of(role) : user.roles());
            roles.add(role);
            User updated = user.withRoles(roles, now);
            tx.set(Collections.USERS, user.id(), updated);
            return updated;
        }
        User user = new User(auth.uid(), auth.email(), data.fullName().trim(), data.phoneNumber(), null,
                EnumSet.of(role), UserStatus.ACTIVE, Cpf.mask(cpfDigits), NotificationPreferences.defaults(),
                data.termsVersion(), data.privacyVersion(), now, null, now, now);
        tx.set(Collections.USERS, user.id(), user);
        tx.set(Collections.USER_PRIVATE, user.id(),
                new UserPrivate(user.id(), encryptor.encrypt(cpfDigits), cpfHash, data.birthDate().toString(), now));
        tx.create(Collections.CPF_INDEX, cpfHash, new CpfIndex(auth.uid(), now));
        return user;
    }

    public record CpfIndex(String uid, Instant createdAt) {
    }
}
