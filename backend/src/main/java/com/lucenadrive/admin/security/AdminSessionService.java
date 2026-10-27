package com.lucenadrive.admin.security;

import com.lucenadrive.common.web.Ids;
import com.lucenadrive.config.CoreConfig;
import com.lucenadrive.config.LucenaProperties;
import com.lucenadrive.persistence.Collections;
import com.lucenadrive.persistence.DocStore;
import com.lucenadrive.persistence.Query;
import com.lucenadrive.security.RequestContext;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * Emite e valida o token de sessão administrativa (enviado no cabeçalho X-Lucena-MFA).
 * Formato: {sessionId}.{HMAC-SHA256(sessionId|uid)}. O token sozinho não autentica: precisa
 * acompanhar um ID token Firebase válido do mesmo usuário, e a sessão precisa estar ativa no banco.
 */
@Service
public class AdminSessionService {

    private final DocStore store;
    private final LucenaProperties props;
    private final byte[] key;
    private final Clock clock;

    public AdminSessionService(DocStore store, LucenaProperties props, CoreConfig.MfaTokenKey key, Clock clock) {
        this.store = store;
        this.props = props;
        this.key = key.value();
        this.clock = clock;
    }

    public record IssuedSession(String token, AdminSession session) {
    }

    public IssuedSession create(String uid) {
        Instant now = Instant.now(clock);
        AdminSession s = new AdminSession(Ids.random(32), uid, now, now.plus(props.security().adminSessionTtl()), now,
                RequestContext.clientIp(), RequestContext.userAgent(), false, null, null);
        store.set(Collections.ADMIN_SESSIONS, s.id(), s);
        return new IssuedSession(s.id() + "." + sign(s.id(), uid), s);
    }

    public Optional<AdminSession> validate(String token, String uid) {
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String id = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!MessageDigest.isEqual(sign(id, uid).getBytes(StandardCharsets.US_ASCII), sig.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        return store.get(Collections.ADMIN_SESSIONS, id, AdminSession.class)
                .filter(s -> s.uid().equals(uid) && !s.revoked() && s.expiresAt().isAfter(now));
    }

    public void stepUp(String sessionId) {
        store.get(Collections.ADMIN_SESSIONS, sessionId, AdminSession.class)
                .ifPresent(s -> store.set(Collections.ADMIN_SESSIONS, s.id(), s.stepUp(Instant.now(clock))));
    }

    public List<AdminSession> listActive(String uid) {
        Instant now = Instant.now(clock);
        return store.query(Query.of(Collections.ADMIN_SESSIONS).eq("uid", uid).eq("revoked", false)
                        .orderBy("createdAt", Query.Direction.DESC).limit(50), AdminSession.class)
                .stream().filter(s -> s.expiresAt().isAfter(now)).toList();
    }

    public boolean revoke(String sessionId, String ownerUid, String revokedBy) {
        return store.get(Collections.ADMIN_SESSIONS, sessionId, AdminSession.class)
                .filter(s -> s.uid().equals(ownerUid))
                .map(s -> {
                    store.set(Collections.ADMIN_SESSIONS, s.id(), s.revoke(revokedBy, Instant.now(clock)));
                    return true;
                }).orElse(false);
    }

    public int revokeAll(String uid, String revokedBy) {
        List<AdminSession> active = listActive(uid);
        active.forEach(s -> store.set(Collections.ADMIN_SESSIONS, s.id(), s.revoke(revokedBy, Instant.now(clock))));
        return active.size();
    }

    private String sign(String sessionId, String uid) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal((sessionId + "|" + uid).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
