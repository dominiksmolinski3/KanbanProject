package pl.myproject.kanbanproject2.config.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deployed app is configured entirely through environment variables, and a name that does not
 * bind fails silently - {@code WebSocketConfig} would keep relaying to {@code localhost} and every
 * board update would fail to reach anyone. These tests pin the names {@code terraform/modules/api_app}
 * and {@code terraform/modules/broker} set, the same shape {@code AuthRateLimitPropertiesBindingTest}
 * already pins for Redis.
 */
class StompRelayPropertiesBindingTest {

    @Test
    @DisplayName("with nothing configured the relay points at a plain local broker")
    void defaultsAreLocal() {
        StompRelayProperties properties = bind(Map.of());

        assertThat(properties.host()).isEqualTo("localhost");
        assertThat(properties.port()).isEqualTo(61613);
        assertThat(properties.username()).isEqualTo("guest");
        assertThat(properties.password()).isEqualTo("guest");
    }

    @Test
    @DisplayName("STOMP_RELAY_* is the name the API module's env block sets")
    void bindsFromTheEnvironment() {
        StompRelayProperties properties = bind(Map.ofEntries(
                Map.entry("STOMP_RELAY_HOST", "kanban-broker-dev"),
                Map.entry("STOMP_RELAY_PORT", "61613"),
                Map.entry("STOMP_RELAY_USERNAME", "kanban"),
                Map.entry("STOMP_RELAY_PASSWORD", "s3cret")));

        assertThat(properties.host()).isEqualTo("kanban-broker-dev");
        assertThat(properties.port()).isEqualTo(61613);
        assertThat(properties.username()).isEqualTo("kanban");
        assertThat(properties.password()).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("a blank host is refused rather than silently relaying nowhere")
    void refusesABlankHost() {
        assertThatThrownBy(() -> new StompRelayProperties(" ", 61613, "guest", "guest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stomp.relay.host");
    }

    @Test
    @DisplayName("a port outside the valid range is refused")
    void refusesAnInvalidPort() {
        assertThatThrownBy(() -> new StompRelayProperties("localhost", 0, "guest", "guest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stomp.relay.port");

        assertThatThrownBy(() -> new StompRelayProperties("localhost", 70000, "guest", "guest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stomp.relay.port");
    }

    private static StompRelayProperties bind(Map<String, Object> environmentVariables) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables));

        return Binder.get(environment)
                .bindOrCreate("stomp.relay", StompRelayProperties.class);
    }
}
