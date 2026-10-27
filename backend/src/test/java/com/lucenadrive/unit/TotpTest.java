package com.lucenadrive.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucenadrive.admin.security.Totp;
import org.junit.jupiter.api.Test;

class TotpTest {

    /** Vetor do RFC 6238 (SHA1, segredo ASCII "12345678901234567890"), truncado para 6 dígitos. */
    static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesRfc6238Vectors() {
        assertThat(Totp.code(RFC_SECRET, Totp.timeStep(59))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Totp.timeStep(1111111109))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Totp.timeStep(2000000000))).isEqualTo("279037");
    }

    @Test
    void acceptsAdjacentStepAndRejectsOthers() {
        String secret = Totp.newSecret();
        long t = 1_800_000_000L;
        String current = Totp.code(secret, Totp.timeStep(t));
        assertThat(Totp.verify(secret, current, t)).isEqualTo(Totp.timeStep(t));
        assertThat(Totp.verify(secret, current, t + 30)).isEqualTo(Totp.timeStep(t));
        assertThat(Totp.verify(secret, current, t + 90)).isEqualTo(-1);
        assertThat(Totp.verify(secret, "abc123", t)).isEqualTo(-1);
    }

    @Test
    void buildsOtpauthUri() {
        assertThat(Totp.otpauthUri("Lucena Drive Admin", "dono@exemplo.test", "ABC"))
                .startsWith("otpauth://totp/Lucena%20Drive%20Admin%3Adono%40exemplo.test?secret=ABC");
    }
}
