package pl.myproject.kanbanproject2.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import pl.myproject.kanbanproject2.config.security.JwtAuthenticationFilter;
import pl.myproject.kanbanproject2.config.security.SecurityConfiguration;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;
import pl.myproject.kanbanproject2.config.websocket.WebSocketAuthInterceptor;
import pl.myproject.kanbanproject2.config.websocket.WebSocketConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The REST surface and the SockJS handshake need the same origins, and used to hold a copy each.
 * What is worth locking down is that there is now one list and that both sides read it — the drift
 * between the two copies is the bug this replaced, not a typo in either of them.
 */
class AllowedOriginsTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(Enable.class);

    @Configuration
    @EnableConfigurationProperties(AllowedOriginsProperties.class)
    static class Enable {
    }

    @Test
    @DisplayName("the defaults are the origins the HTTP side already allowed")
    void defaultsToTheStricterOfTheTwoLists() {
        contextRunner.run(context -> assertThat(context.getBean(AllowedOriginsProperties.class).allowedOrigins())
                .containsExactly(
                        "https://kanbanproject.pl",
                        "https://www.kanbanproject.pl",
                        "http://localhost:5173",
                        "http://localhost:3000",
                        "http://localhost:5174",
                        "http://localhost:8080",
                        "http://localhost:80",
                        "http://127.0.0.1:8080",
                        "http://app:8080"));
    }

    @Test
    @DisplayName("the plaintext production origins only the WebSocket copy carried are gone")
    void dropsThePlaintextProductionOrigins() {
        contextRunner.run(context -> assertThat(context.getBean(AllowedOriginsProperties.class).allowedOrigins())
                .doesNotContain("http://kanbanproject.pl", "http://www.kanbanproject.pl"));
    }

    @Test
    @DisplayName("a deployment can replace the list without a rebuild")
    void bindsFromConfiguration() {
        contextRunner
                .withPropertyValues("security.cors.allowed-origins=https://board.example,https://www.board.example")
                .run(context -> assertThat(context.getBean(AllowedOriginsProperties.class).allowedOrigins())
                        .containsExactly("https://board.example", "https://www.board.example"));
    }

    @Test
    @DisplayName("an empty list stops the context rather than silently refusing every browser")
    void refusesAnEmptyList() {
        contextRunner.withPropertyValues("security.cors.allowed-origins=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a wildcard is refused, because both consumers send credentials")
    void refusesAWildcard() {
        assertThatThrownBy(() -> new AllowedOriginsProperties(List.of("*")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the REST surface is configured from it")
    void theCorsSourceReadsIt() {
        var origins = new AllowedOriginsProperties(List.of("https://board.example", "http://localhost:5173"));
        var security = new SecurityConfiguration(
                mock(JwtAuthenticationFilter.class),
                mock(AuthenticationProvider.class),
                mock(AuthRateLimitProperties.class),
                mock(AuthRateLimiter.class),
                mock(ClientIpResolver.class),
                new ObjectMapper(),
                origins);

        var source = (UrlBasedCorsConfigurationSource) security.corsConfigurationSource();
        var request = new MockHttpServletRequest("GET", "/api/tasks");

        assertThat(source.getCorsConfiguration(request).getAllowedOrigins())
                .containsExactlyElementsOf(origins.allowedOrigins());
    }

    @Test
    @DisplayName("the SockJS handshake is configured from it too")
    void theStompEndpointReadsIt() {
        var origins = new AllowedOriginsProperties(List.of("https://board.example", "http://localhost:5173"));
        var registration = mock(StompWebSocketEndpointRegistration.class, RETURNS_SELF);
        var registry = mock(StompEndpointRegistry.class);
        when(registry.addEndpoint("/ws")).thenReturn(registration);

        new WebSocketConfig(mock(WebSocketAuthInterceptor.class), origins).registerStompEndpoints(registry);

        var passed = ArgumentCaptor.forClass(String[].class);
        verify(registration).setAllowedOrigins(passed.capture());
        assertThat(passed.getValue()).containsExactly(origins.asArray());
    }

    @Test
    @DisplayName("the list cannot be mutated through the record")
    void isImmutable() {
        var origins = new AllowedOriginsProperties(List.of("https://board.example"));

        assertThatThrownBy(() -> origins.allowedOrigins().add("https://elsewhere.example"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
