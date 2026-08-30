package pl.myproject.kanbanproject2.config.security.captcha;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.auth.CaptchaDto;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * What the verifier does with each answer the provider can give, including the answers that are not
 * an answer. The provider is stubbed rather than called: the point under test is the decision this
 * class makes, and a test that reached Google would fail on an aeroplane and pass on a bad secret.
 */
class CaptchaVerifierTest {

    private static final String VERIFY_URL = "https://captcha.example/siteverify";

    private static CaptchaProperties properties(boolean enabled, String secret) {
        return new CaptchaProperties(
                enabled, secret, VERIFY_URL, Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    private record Fixture(CaptchaVerifier verifier, MockRestServiceServer server) {
    }

    private static Fixture fixture(boolean enabled) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new CaptchaVerifier(properties(enabled, "a-secret"), builder.build()), server);
    }

    @Nested
    @DisplayName("when verification is off")
    class Disabled {

        @Test
        @DisplayName("nothing is sent anywhere, with or without a token")
        void doesNotCallTheProvider() {
            var fixture = fixture(false);

            assertThatCode(() -> fixture.verifier().verify(new CaptchaDto("anything"), "1.2.3.4"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> fixture.verifier().verify(null, "1.2.3.4"))
                    .doesNotThrowAnyException();

            fixture.server().verify();
        }
    }

    @Nested
    @DisplayName("when verification is on")
    class Enabled {

        @Test
        @DisplayName("a token the provider accepts passes")
        void successPasses() {
            var fixture = fixture(true);
            fixture.server().expect(requestTo(VERIFY_URL))
                    .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

            assertThatCode(() -> fixture.verifier().verify(new CaptchaDto("good"), "1.2.3.4"))
                    .doesNotThrowAnyException();

            fixture.server().verify();
        }

        @Test
        @DisplayName("the secret, the token and the caller's address are what gets posted")
        void sendsTheFormTheProviderExpects() {
            var fixture = fixture(true);
            fixture.server().expect(requestTo(VERIFY_URL))
                    .andExpect(content().string("secret=a-secret&response=good&remoteip=1.2.3.4"))
                    .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

            fixture.verifier().verify(new CaptchaDto("good"), "1.2.3.4");

            fixture.server().verify();
        }

        @Test
        @DisplayName("an unresolved address is left off rather than sent as a placeholder")
        void omitsAnAbsentAddress() {
            var fixture = fixture(true);
            fixture.server().expect(requestTo(VERIFY_URL))
                    .andExpect(content().string("secret=a-secret&response=good"))
                    .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

            fixture.verifier().verify(new CaptchaDto("good"), null);

            fixture.server().verify();
        }

        @Test
        @DisplayName("a token the provider rejects is CAPTCHA_FAILED")
        void rejectionFails() {
            var fixture = fixture(true);
            fixture.server().expect(requestTo(VERIFY_URL))
                    .andRespond(withSuccess(
                            "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}",
                            MediaType.APPLICATION_JSON));

            assertCaptchaFailed(() -> fixture.verifier().verify(new CaptchaDto("stale"), "1.2.3.4"));
        }

        @Test
        @DisplayName("no token at all is the same failure, not a skip")
        void aMissingTokenIsAFailure() {
            var fixture = fixture(true);

            assertCaptchaFailed(() -> fixture.verifier().verify(null, "1.2.3.4"));
            assertCaptchaFailed(() -> fixture.verifier().verify(new CaptchaDto(null), "1.2.3.4"));
            assertCaptchaFailed(() -> fixture.verifier().verify(new CaptchaDto("  "), "1.2.3.4"));

            // Nothing was asked of the provider, so nothing can have been waved through by it.
            fixture.server().verify();
        }

        @Test
        @DisplayName("a provider that answers with an error fails closed")
        void providerErrorFailsClosed() {
            var fixture = fixture(true);
            fixture.server().expect(requestTo(VERIFY_URL)).andRespond(withServerError());

            assertCaptchaFailed(() -> fixture.verifier().verify(new CaptchaDto("good"), "1.2.3.4"));
        }

        @Test
        @DisplayName("a body that is not the expected shape fails closed")
        void unreadableBodyFailsClosed() {
            var fixture = fixture(true);
            fixture.server().expect(requestTo(VERIFY_URL))
                    .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

            assertCaptchaFailed(() -> fixture.verifier().verify(new CaptchaDto("good"), "1.2.3.4"));
        }
    }

    @Test
    @DisplayName("enabled with no secret refuses to start rather than failing every request")
    void enabledWithoutASecretIsAStartupFailure() {
        assertThatThrownBy(() ->
                new CaptchaVerifier(properties(true, "  "), RestClient.builder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAPTCHA_SECRET");
    }

    @Test
    @DisplayName("disabled with no secret is a normal configuration and starts")
    void disabledWithoutASecretStarts() {
        assertThatCode(() ->
                new CaptchaVerifier(properties(false, ""), RestClient.builder().build()))
                .doesNotThrowAnyException();
    }

    private static void assertCaptchaFailed(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.CAPTCHA_FAILED);
        assertThat(ExceptionIdentifier.CAPTCHA_FAILED.getStatus().value()).isEqualTo(400);
    }
}
