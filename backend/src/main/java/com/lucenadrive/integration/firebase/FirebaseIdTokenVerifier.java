package com.lucenadrive.integration.firebase;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.lucenadrive.security.IdTokenVerifier;
import com.lucenadrive.security.VerifiedToken;
import java.time.Instant;
import java.util.Map;

public class FirebaseIdTokenVerifier implements IdTokenVerifier {

    private final FirebaseAuth auth;
    private final boolean checkRevoked;

    public FirebaseIdTokenVerifier(FirebaseAuth auth, boolean checkRevoked) {
        this.auth = auth;
        this.checkRevoked = checkRevoked;
    }

    @Override
    public VerifiedToken verify(String idToken) {
        try {
            FirebaseToken t = auth.verifyIdToken(idToken, checkRevoked);
            Map<String, Object> claims = t.getClaims();
            Object authTime = claims.get("auth_time");
            Object phone = claims.get("phone_number");
            String provider = null;
            if (claims.get("firebase") instanceof Map<?, ?> fb && fb.get("sign_in_provider") != null) {
                provider = fb.get("sign_in_provider").toString();
            }
            return new VerifiedToken(t.getUid(), t.getEmail(), t.isEmailVerified(),
                    phone == null ? null : phone.toString(), t.getName(),
                    authTime instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null, provider);
        } catch (FirebaseAuthException | IllegalArgumentException e) {
            throw new InvalidTokenException("Token inválido", e);
        }
    }
}
