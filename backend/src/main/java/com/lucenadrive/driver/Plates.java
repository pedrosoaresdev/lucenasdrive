package com.lucenadrive.driver;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Normalização e validação de placas (padrão antigo ABC1234 e Mercosul ABC1D23). */
public final class Plates {

    private static final Pattern PLATE = Pattern.compile("^[A-Z]{3}[0-9][A-Z0-9][0-9]{2}$");

    private Plates() {
    }

    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String p = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return PLATE.matcher(p).matches() ? Optional.of(p) : Optional.empty();
    }
}
