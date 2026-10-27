package com.lucenadrive.support;

import java.util.concurrent.atomic.AtomicInteger;

/** Gera CPFs fictícios matematicamente válidos para testes (não correspondem a pessoas reais). */
public final class TestCpfs {

    private static final AtomicInteger SEQ = new AtomicInteger(100_000_000);

    private TestCpfs() {
    }

    public static String next() {
        String base = String.valueOf(SEQ.incrementAndGet());
        int d1 = digit(base, 10);
        int d2 = digit(base + d1, 11);
        return base + d1 + d2;
    }

    private static int digit(String s, int weight) {
        int sum = 0;
        for (int i = 0; i < s.length(); i++) {
            sum += (s.charAt(i) - '0') * (weight - i);
        }
        int r = (sum * 10) % 11;
        return r == 10 ? 0 : r;
    }
}
