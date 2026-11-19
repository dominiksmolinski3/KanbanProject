package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class PinEvaluatorTest {

    private static final byte[] GENERATOR = HexFormat.of().parseHex(
            "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2"
                    + "964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5");

    private static PinEvaluator withSecret(String secret) {
        return new PinEvaluator(new MessagingProperties(true, secret));
    }

    @Test
    @DisplayName("answers with the x coordinate the browser's unblinding expects (same vector as pin.test.ts)")
    void matchesTheClientVector() {
        assertThat(withSecret("test-pin-secret").evaluate(GENERATOR))
                .hasValueSatisfying(x -> assertThat(HexFormat.of().formatHex(x))
                        .isEqualTo("9dc2918299876cd3b23bf5dab8d64878b286c1a79edbc9c0ccbe517643d67eb8"));
    }

    @Test
    @DisplayName("another secret gives another answer")
    void dependsOnTheSecret() {
        assertThat(withSecret("other").evaluate(GENERATOR).orElseThrow())
                .isNotEqualTo(withSecret("test-pin-secret").evaluate(GENERATOR).orElseThrow());
    }

    @Test
    @DisplayName("refuses anything that is not an uncompressed point on P-256")
    void refusesNonPoints() {
        PinEvaluator evaluator = withSecret("test-pin-secret");
        byte[] offCurve = GENERATOR.clone();
        offCurve[64] ^= 1;
        byte[] compressed = new byte[65];
        compressed[0] = 0x02;

        assertThat(evaluator.evaluate(offCurve)).isEmpty();
        assertThat(evaluator.evaluate(compressed)).isEmpty();
        assertThat(evaluator.evaluate(new byte[33])).isEmpty();
        assertThat(evaluator.evaluate(null)).isEmpty();
    }

    @Test
    @DisplayName("with no secret configured it answers nothing and says so")
    void unconfigured() {
        PinEvaluator evaluator = withSecret("");

        assertThat(evaluator.isConfigured()).isFalse();
        assertThat(evaluator.evaluate(GENERATOR)).isEmpty();
        assertThat(withSecret("x").isConfigured()).isTrue();
    }
}
