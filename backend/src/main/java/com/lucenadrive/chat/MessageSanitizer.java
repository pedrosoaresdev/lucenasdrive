package com.lucenadrive.chat;

/**
 * Normaliza texto de chat: remove caracteres de controle (exceto quebra de linha), limita quebras
 * consecutivas e espaços. A renderização no Angular usa interpolação (escape automático), nunca innerHTML.
 */
public final class MessageSanitizer {

    private MessageSanitizer() {
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r\n", "\n")
                .replaceAll("[\\p{Cntrl}&&[^\n]]", "")
                .replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "")
                .replaceAll("\n{3,}", "\n\n")
                .replaceAll("[ \\t]{2,}", " ");
        return s.strip();
    }
}
