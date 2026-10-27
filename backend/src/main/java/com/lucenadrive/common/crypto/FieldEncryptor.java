package com.lucenadrive.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Criptografia de campos sensíveis (CPF, CNH, chave Pix, segredo TOTP) com AES-256-GCM e
 * hash determinístico (HMAC-SHA256) para índices de unicidade sem expor o valor.
 * A chave vem de variável de ambiente; em produção deve vir de um gerenciador de segredos/KMS.
 */
public class FieldEncryptor {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecretKeySpec hmacKey;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptor(byte[] rawKey) {
        if (rawKey.length != 32) {
            throw new IllegalArgumentException("A chave de criptografia deve ter 32 bytes (AES-256).");
        }
        this.key = new SecretKeySpec(rawKey, "AES");
        byte[] derived = sha256(concat(rawKey, "lucena-index-hmac".getBytes(StandardCharsets.UTF_8)));
        this.hmacKey = new SecretKeySpec(derived, "HmacSHA256");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(concat(iv, ct));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao criptografar campo", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || !encoded.startsWith("v1:")) {
            throw new IllegalArgumentException("Formato de campo criptografado desconhecido");
        }
        try {
            byte[] all = Base64.getDecoder().decode(encoded.substring(3));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao descriptografar campo", e);
        }
    }

    /** Hash estável para chaves de índice (ex.: unicidade de CPF/placa). */
    public String indexHash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String data) {
        return HexFormat.of().formatHex(sha256(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
