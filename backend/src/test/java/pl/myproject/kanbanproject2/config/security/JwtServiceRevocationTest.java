package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import pl.myproject.kanbanproject2.user.User;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceRevocationTest {

    private JwtService jwtService;
    private User user;
    private String token;
    private Instant issuedAt;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", Base64.getEncoder().encodeToString(new byte[64]));
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", 900_000L);
        user = new User("someone", "someone@example.test", "hashed");
        token = jwtService.generateToken(user);
        issuedAt = jwtService.issuedAt(token);
    }

    @Test
    @DisplayName("an account that never signed out everywhere keeps its tokens")
    void noRevocationKeepsTheToken() {
        assertThat(jwtService.isTokenValid(token, user)).isTrue();
    }

    @Test
    @DisplayName("signing out everywhere ends a token issued before it, though it has not expired")
    void revocationEndsAnOlderToken() {
        user.setSessionsRevokedAt(issuedAt.plusSeconds(1));

        assertThat(jwtService.isTokenValid(token, user))
                .as("the access token outlived the password change or the theft by up to fifteen minutes")
                .isFalse();
    }

    @Test
    @DisplayName("a token issued in the same second as the revocation survives, since iat has whole seconds")
    void theSessionIssuedRightAfterTheRevocationIsKept() {
        user.setSessionsRevokedAt(issuedAt.plus(Duration.ofMillis(900)));

        assertThat(jwtService.isTokenValid(token, user))
                .as("an email change issues a new session in the same request as its revocation")
                .isTrue();
    }

    @Test
    @DisplayName("a revocation older than the token leaves it alone")
    void anOlderRevocationLeavesANewerToken() {
        user.setSessionsRevokedAt(issuedAt.minusSeconds(30));

        assertThat(jwtService.isTokenValid(token, user)).isTrue();
    }
}
