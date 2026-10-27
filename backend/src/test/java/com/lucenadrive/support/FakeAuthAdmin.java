package com.lucenadrive.support;

import com.lucenadrive.integration.AuthAdmin;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FakeAuthAdmin implements AuthAdmin {

    private final FakeIdTokenVerifier verifier;
    public final Set<String> revoked = ConcurrentHashMap.newKeySet();
    public final Set<String> deleted = ConcurrentHashMap.newKeySet();

    public FakeAuthAdmin(FakeIdTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public void revokeSessions(String uid) {
        revoked.add(uid);
    }

    @Override
    public void disableAccount(String uid, boolean disabled) {
    }

    @Override
    public void deleteAccount(String uid) {
        deleted.add(uid);
    }

    @Override
    public Optional<String> uidByEmail(String email) {
        return verifier.accounts().values().stream().filter(a -> email.equalsIgnoreCase(a.email()))
                .map(FakeIdTokenVerifier.Account::uid).findFirst();
    }
}
