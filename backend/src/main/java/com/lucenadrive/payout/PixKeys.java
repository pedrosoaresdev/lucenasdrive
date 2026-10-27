package com.lucenadrive.payout;

import com.lucenadrive.common.cpf.Cpf;
import com.lucenadrive.common.error.ApiException;
import com.lucenadrive.common.error.ErrorCode;
import java.util.Locale;

/** Validação e mascaramento de chaves Pix. */
public final class PixKeys {

    private PixKeys() {
    }

    public static String normalize(PixKey.KeyType type, String raw) {
        String v = raw == null ? "" : raw.trim();
        String normalized = switch (type) {
            case CPF -> Cpf.isValid(v) ? Cpf.digitsOnly(v) : null;
            case CNPJ -> v.replaceAll("\\D", "").length() == 14 ? v.replaceAll("\\D", "") : null;
            case EMAIL -> v.matches("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$") && v.length() <= 77 ? v.toLowerCase(Locale.ROOT) : null;
            case PHONE -> v.matches("^\\+55\\d{10,11}$") ? v : null;
            case EVP -> v.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                    ? v.toLowerCase(Locale.ROOT) : null;
        };
        if (normalized == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chave Pix inválida para o tipo " + type + ".");
        }
        return normalized;
    }

    public static String mask(PixKey.KeyType type, String key) {
        return switch (type) {
            case CPF -> Cpf.mask(key);
            case CNPJ -> "**." + key.substring(2, 5) + "." + key.substring(5, 8) + "/****-**";
            case EMAIL -> key.charAt(0) + "***" + key.substring(key.indexOf('@'));
            case PHONE -> key.substring(0, 5) + "*****" + key.substring(key.length() - 4);
            case EVP -> key.substring(0, 8) + "-****-****-****-************";
        };
    }
}
