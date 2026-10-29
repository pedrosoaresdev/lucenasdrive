package com.lucenadrive.admin.reports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Gera CSV no padrão do Excel em português: separador ";", vírgula decimal, datas no fuso de Brasília e BOM UTF-8.
 * Células que começariam uma fórmula (=, +, -, @) são prefixadas com apóstrofo (proteção contra injeção de CSV).
 */
public final class Csv {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.of("America/Sao_Paulo"));

    private final StringBuilder sb = new StringBuilder("﻿");

    public Csv row(List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(cell(cells.get(i)));
        }
        sb.append("\r\n");
        return this;
    }

    static String cell(Object v) {
        if (v == null) {
            return "";
        }
        String s = v instanceof Instant i ? DATE.format(i) : String.valueOf(v);
        boolean plainNumber = s.matches("-?\\d+([.,]\\d+)?");
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number) && !plainNumber) {
            s = "'" + s;
        }
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** Centavos → "1234,56". */
    public static String money(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString().replace('.', ',');
    }

    public static String decimal(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    public byte[] bytes() {
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
