package com.lucenadrive.passenger;

import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.security.AuthUser;
import com.lucenadrive.security.Role;
import com.lucenadrive.user.IdentityRegistrationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PassengerService {

    private final DocStore store;
    private final IdentityRegistrationService identity;
    private final Clock clock;

    public PassengerService(DocStore store, IdentityRegistrationService identity, Clock clock) {
        this.store = store;
        this.identity = identity;
        this.clock = clock;
    }

    public PassengerProfile register(AuthUser auth, IdentityRegistrationService.IdentityData data) {
        return store.transaction(tx -> {
            boolean exists = tx.get(Collections.PASSENGER_PROFILES, auth.uid(), PassengerProfile.class).isPresent();
            identity.registerInTx(tx, auth, data, Role.PASSENGER);
            if (exists) {
                throw new ApiException(ErrorCode.ALREADY_REGISTERED, "Cadastro de passageiro já existe.");
            }
            Instant now = Instant.now(clock);
            PassengerProfile p = new PassengerProfile(auth.uid(), List.of(), List.of(), 0, 0, now, now, 0, 0);
            tx.set(Collections.PASSENGER_PROFILES, auth.uid(), p);
            return p;
        });
    }

    public PassengerProfile profile(String uid) {
        return store.get(Collections.PASSENGER_PROFILES, uid, PassengerProfile.class)
                .orElseThrow(() -> new ApiException(ErrorCode.PROFILE_NOT_FOUND, "Cadastro de passageiro não encontrado."));
    }

    public PassengerProfile updateFavorites(AuthUser auth, List<PassengerProfile.FavoritePlace> favorites) {
        if (favorites.size() > 10) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Máximo de 10 locais favoritos.");
        }
        long homes = favorites.stream().filter(f -> f.kind() == PassengerProfile.FavoritePlace.Kind.HOME).count();
        long works = favorites.stream().filter(f -> f.kind() == PassengerProfile.FavoritePlace.Kind.WORK).count();
        if (homes > 1 || works > 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Apenas um endereço de Casa e um de Trabalho.");
        }
        return store.transaction(tx -> {
            PassengerProfile p = tx.get(Collections.PASSENGER_PROFILES, auth.uid(), PassengerProfile.class)
                    .orElseThrow(() -> new ApiException(ErrorCode.PROFILE_NOT_FOUND, "Cadastro não encontrado."));
            PassengerProfile next = p.withFavorites(favorites, Instant.now(clock));
            tx.set(Collections.PASSENGER_PROFILES, p.id(), next);
            return next;
        });
    }
}
