package com.lucenadrive.payment.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verificação HMAC-SHA256 do corpo bruto (cabeçalho "sha256=&lt;hex&gt;"). Provedores com outro
 * esquema (token fixo, assinatura RSA, mTLS) devem ter verificador próprio no adaptador.
 */
public final class WebhookSignature {

    private WebhookSignature() {
    }

    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(String secret, byte[] body, String header) {
        if (secret == null || secret.isBlank() || header == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, body).getBytes(StandardCharsets.US_ASCII),
                header.trim().getBytes(StandardCharsets.US_ASCII));
    }
}
