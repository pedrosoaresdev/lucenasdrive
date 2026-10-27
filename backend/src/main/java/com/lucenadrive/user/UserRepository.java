package com.lucenadrive.user;

import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final DocStore store;

    public UserRepository(DocStore store) {
        this.store = store;
    }

    public Optional<User> findById(String uid) {
        return store.get(Collections.USERS, uid, User.class);
    }

    public User require(String uid) {
        return findById(uid).orElseThrow(() -> new com.lucenadrive.common.error.ApiException(
                com.lucenadrive.common.error.ErrorCode.PROFILE_NOT_FOUND, "Cadastro não encontrado."));
    }

    public void save(User user) {
        store.set(Collections.USERS, user.id(), user);
    }
}
