package com.lucenadrive.common.cpf;

/**
 * Validação matemática de CPF (formato e dígitos verificadores). Um CPF válido aqui NÃO significa
 * identidade confirmada nem situação cadastral regular na Receita Federal.
 */
public final class Cpf {

    private Cpf() {
    }

    public static String digitsOnly(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    public static boolean isValid(String raw) {
        String cpf = digitsOnly(raw);
        if (cpf.length() != 11 || cpf.chars().distinct().count() == 1) {
            return false;
        }
        int d1 = checkDigit(cpf, 9);
        int d2 = checkDigit(cpf, 10);
        return d1 == cpf.charAt(9) - '0' && d2 == cpf.charAt(10) - '0';
    }

    private static int checkDigit(String cpf, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (cpf.charAt(i) - '0') * (length + 1 - i);
        }
        int rest = (sum * 10) % 11;
        return rest == 10 ? 0 : rest;
    }

    /** Máscara para exibição: ***.456.789-** */
    public static String mask(String raw) {
        String cpf = digitsOnly(raw);
        if (cpf.length() != 11) {
            return "***";
        }
        return "***." + cpf.substring(3, 6) + "." + cpf.substring(6, 9) + "-**";
    }
}
