package com.lucenadrive.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lucenadrive.chat.MessageSanitizer;
import com.lucenadrive.common.crypto.FieldEncryptor;
import com.lucenadrive.common.money.Money;
import com.lucenadrive.driver.Plates;
import com.lucenadrive.geo.GeoMath;
import com.lucenadrive.geo.GeoPoint;
import com.lucenadrive.payment.webhook.WebhookSignature;
import com.lucenadrive.payout.PixKey;
import com.lucenadrive.payout.PixKeys;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MiscUnitTest {

    @Test
    void pointInPolygon() {
        List<GeoPoint> square = List.of(new GeoPoint(0, 0), new GeoPoint(0, 1), new GeoPoint(1, 1), new GeoPoint(1, 0));
        assertThat(GeoMath.contains(square, new GeoPoint(0.5, 0.5))).isTrue();
        assertThat(GeoMath.contains(square, new GeoPoint(1.5, 0.5))).isFalse();
        assertThat(GeoMath.contains(List.of(), new GeoPoint(0, 0))).isFalse();
    }

    @Test
    void haversineDistance() {
        // ~1 grau de latitude ≈ 111 km
        assertThat(GeoMath.haversineMeters(new GeoPoint(0, 0), new GeoPoint(1, 0))).isBetween(111_000.0, 111_400.0);
    }

    @Test
    void sanitizerStripsControlAndBidiCharacters() {
        assertThat(MessageSanitizer.sanitize("  olá\u0000‮ mundo\n\n\n\nfim  ")).isEqualTo("olá mundo\n\nfim");
        assertThat(MessageSanitizer.sanitize("<script>alert(1)</script>")).isEqualTo("<script>alert(1)</script>");
    }

    @Test
    void encryptorRoundTripAndDeterministicIndex() {
        FieldEncryptor enc = new FieldEncryptor(new byte[32]);
        String ct = enc.encrypt("52998224725");
        assertThat(ct).startsWith("v1:").doesNotContain("52998224725");
        assertThat(enc.decrypt(ct)).isEqualTo("52998224725");
        assertThat(enc.encrypt("x")).isNotEqualTo(enc.encrypt("x")); // IV aleatório
        assertThat(enc.indexHash("a")).isEqualTo(enc.indexHash("a"));
        assertThatThrownBy(() -> new FieldEncryptor(new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void webhookSignature() {
        byte[] body = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
        String sig = WebhookSignature.sign("segredo", body);
        assertThat(WebhookSignature.verify("segredo", body, sig)).isTrue();
        assertThat(WebhookSignature.verify("outro", body, sig)).isFalse();
        assertThat(WebhookSignature.verify("segredo", "{\"id\":2}".getBytes(StandardCharsets.UTF_8), sig)).isFalse();
        assertThat(WebhookSignature.verify("segredo", body, null)).isFalse();
    }

    @Test
    void platesAndPixKeys() {
        assertThat(Plates.normalize("abc-1d23")).contains("ABC1D23");
        assertThat(Plates.normalize("ABC1234")).contains("ABC1234");
        assertThat(Plates.normalize("AB12345")).isEmpty();
        assertThat(PixKeys.mask(PixKey.KeyType.EMAIL, PixKeys.normalize(PixKey.KeyType.EMAIL, "Fulano@Exemplo.test")))
                .isEqualTo("f***@exemplo.test");
        assertThatThrownBy(() -> PixKeys.normalize(PixKey.KeyType.CPF, "111.111.111-11"));
    }

    @Test
    void moneyBasisPoints() {
        assertThat(Money.applyBasisPoints(1_005, 5_000)).isEqualTo(503); // 502,5 → 503 (HALF_UP)
        assertThat(Money.format(12_345)).isEqualTo("R$ 123,45");
    }
}
