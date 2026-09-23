package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.ActiveDeviceDto;
import pl.myproject.kanbanproject2.user.auth.DemoAccounts;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceListRedactionTest {

    private static final Instant SIGNED_IN = Instant.parse("2026-09-20T08:00:00Z");
    private static final Instant RENEWED = Instant.parse("2026-09-24T08:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-24T08:00:00Z");

    private RefreshTokenService refreshTokenService;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        refreshTokenService = mock(RefreshTokenService.class);
        service = new AuthenticationService(mock(UserRepository.class), mock(PasswordEncoder.class),
                mock(AuthenticationManager.class), mock(EmailService.class), mock(JwtService.class),
                refreshTokenService, new DemoAccounts(List.of("demo@example.test")));
    }

    private User withSessions(String email) {
        var user = new User("someone", email, "hashed");
        when(refreshTokenService.listSessionsFor(user)).thenReturn(List.of(new ActiveDeviceDto(
                5L, "203.0.113.7", "Mozilla/5.0", SIGNED_IN, RENEWED, EXPIRES, false)));
        return user;
    }

    @Test
    @DisplayName("a demo account's sessions keep what can be ended and lose where they are from")
    void aDemoAccountSeesNoAddressesOrBrowsers() {
        var sessions = service.listSessions(withSessions("demo@example.test"));

        assertThat(sessions).containsExactly(
                new ActiveDeviceDto(5L, null, null, SIGNED_IN, RENEWED, EXPIRES, true));
    }

    @Test
    @DisplayName("an ordinary account sees its own sessions in full")
    void anOrdinaryAccountSeesEverything() {
        var sessions = service.listSessions(withSessions("someone@example.test"));

        assertThat(sessions).singleElement().satisfies(session -> {
            assertThat(session.ipAddress()).isEqualTo("203.0.113.7");
            assertThat(session.userAgent()).isEqualTo("Mozilla/5.0");
            assertThat(session.redacted()).isFalse();
        });
    }
}
