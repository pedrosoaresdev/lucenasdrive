package com.lucenadrive.support;

import com.lucenadrive.security.IdTokenVerifier;
import com.lucenadrive.security.VerifiedToken;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Substitui o Firebase Auth nos testes: token "test-{uid}". authTime = agora, salvo se marcado como antigo. */
public class FakeIdTokenVerifier implements IdTokenVerifier {

    public record Account(String uid, String email, boolean emailVerified, Instant staleAuthTime) {
    }

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Clock clock;

    public FakeIdTokenVerifier(Clock clock) {
        this.clock = clock;
    }

    public String register(String uid, String email, boolean verified) {
        accounts.put(uid, new Account(uid, email, verified, null));
        return "test-" + uid;
    }

    public void setStaleLogin(String uid, Instant authTime) {
        Account a = accounts.get(uid);
        accounts.put(uid, new Account(a.uid(), a.email(), a.emailVerified(), authTime));
    }

    public Map<String, Account> accounts() {
        return accounts;
    }

    @Override
    public VerifiedToken verify(String idToken) {
        if (idToken == null || !idToken.startsWith("test-")) {
            throw new InvalidTokenException("invalid", null);
        }
        Account a = accounts.get(idToken.substring(5));
        if (a == null) {
            throw new InvalidTokenException("unknown", null);
        }
        return new VerifiedToken(a.uid(), a.email(), a.emailVerified(), null, null,
                a.staleAuthTime() != null ? a.staleAuthTime() : clock.instant(), "password");
    }
}
