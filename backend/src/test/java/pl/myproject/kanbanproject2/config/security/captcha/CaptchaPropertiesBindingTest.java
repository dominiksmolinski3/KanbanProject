package pl.myproject.kanbanproject2.config.security.captcha;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two variable names are the ones docker-compose, Terraform and the Key Vault entry already
 * use, and they are the whole reason this can be turned on without touching any of them. A name
 * that does not bind fails silently - the app would keep the default of "off" and nothing would
 * say so, which is exactly the state this branch exists to end.
 */
class CaptchaPropertiesBindingTest {

    @Test
    @DisplayName("with nothing configured verification is off")
    void defaultsToOff() {
        var properties = bind(Map.of());

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.secret()).isEmpty();
        assertThat(properties.verifyUrl()).isEqualTo("https://www.google.com/recaptcha/api/siteverify");
    }

    @Test
    @DisplayName("the timeouts have bounded defaults - a login must not wait on the provider forever")
    void timeoutsAreBounded() {
        var properties = bind(Map.of());

        assertThat(properties.connectTimeout()).isBetween(Duration.ofMillis(1), Duration.ofSeconds(10));
        assertThat(properties.readTimeout()).isBetween(Duration.ofMillis(1), Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("CAPTCHA_ENABLED and CAPTCHA_SECRET are the names already plumbed to the app")
    void bindsTheNamesTheDeploymentAlreadySets() {
        var environment = new MockEnvironment()
                .withProperty("security.captcha.enabled", "${CAPTCHA_ENABLED:false}")
                .withProperty("security.captcha.secret", "${CAPTCHA_SECRET:}");
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("CAPTCHA_ENABLED", "true", "CAPTCHA_SECRET", "from-the-vault")));

        var properties = Binder.get(environment)
                .bindOrCreate("security.captcha", CaptchaProperties.class);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.secret()).isEqualTo("from-the-vault");
    }

    @Test
    @DisplayName("the provider endpoint and both timeouts are configurable without a rebuild")
    void everythingElseBindsToo() {
        var properties = bind(Map.of(
                "SECURITY_CAPTCHA_VERIFY_URL", "https://hcaptcha.example/siteverify",
                "SECURITY_CAPTCHA_CONNECT_TIMEOUT", "1s",
                "SECURITY_CAPTCHA_READ_TIMEOUT", "2s"));

        assertThat(properties.verifyUrl()).isEqualTo("https://hcaptcha.example/siteverify");
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    private static CaptchaProperties bind(Map<String, Object> environmentVariables) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables));

        return Binder.get(environment).bindOrCreate("security.captcha", CaptchaProperties.class);
    }
}
