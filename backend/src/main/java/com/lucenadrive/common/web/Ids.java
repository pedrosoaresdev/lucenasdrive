package com.lucenadrive.common.web;

import java.security.SecureRandom;

/** Identificadores aleatórios compactos e não sequenciais. */
public final class Ids {

    private static final char[] ALPHABET = "0123456789abcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String newId() {
        return random(20);
    }

    /** Código curto legível para corridas (ex.: LD-7K3M9Q). */
    public static String rideCode() {
        char[] alphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
        StringBuilder sb = new StringBuilder("LD-");
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet[RANDOM.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    public static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
