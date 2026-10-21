package pl.myproject.kanbanproject2.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.config.security.AuthenticationService;
import pl.myproject.kanbanproject2.config.security.JwtService;
import pl.myproject.kanbanproject2.config.security.PasswordResetService;
import pl.myproject.kanbanproject2.config.security.RefreshTokenService;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.mail.EmailService;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.DemoAccounts;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
import pl.myproject.kanbanproject2.user.auth.ResetPasswordRequest;
import pl.myproject.kanbanproject2.user.auth.VerifyUserDto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig({DatabaseTestConfiguration.class, CodeGuessesDatabaseTest.Beans.class})
class CodeGuessesDatabaseTest {

    private static final int GUESSES = 5;

    @Configuration
    static class Beans {
        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }

        @Bean
        PasswordResetService passwordResetService(UserRepository users, PasswordEncoder encoder) {
            return new PasswordResetService(users, encoder, mock(EmailService.class), mock(RefreshTokenService.class));
        }

        @Bean
        AuthenticationService authenticationService(UserRepository users, PasswordEncoder encoder) {
            return new AuthenticationService(users, encoder, mock(AuthenticationManager.class), mock(EmailService.class),
                    mock(JwtService.class), mock(RefreshTokenService.class), new DemoAccounts(List.of()));
        }
    }

    @Autowired
    private PasswordResetService resets;

    @Autowired
    private AuthenticationService authentication;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private User account(boolean enabled) {
        return transactions.execute(status -> {
            var user = new User("someone", "guesses-" + UUID.randomUUID() + "@example.test", encoder.encode("password"));
            user.setEnabled(enabled);
            user.setPasswordResetCode(encoder.encode("111111"));
            user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(10));
            user.setVerificationCode(encoder.encode("111111"));
            user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10));
            return users.save(user);
        });
    }

    @Test
    @DisplayName("wrong reset codes guessed at the same moment are all counted, so the limit retires the code")
    void simultaneousResetGuessesAreAllCounted() throws Exception {
        var user = account(true);

        guessAtOnce(() -> resets.resetPassword(new ResetPasswordRequest(user.getEmail(), "222222", "new-password")));

        assertThat(jdbc.queryForObject("select password_reset_code is null from users where id = ?",
                Boolean.class, user.getId()))
                .as("concurrent guesses overwrote each other's count, so more guesses than the limit were tried")
                .isTrue();
    }

    @Test
    @DisplayName("wrong verification codes guessed at the same moment are all counted, so the limit retires the code")
    void simultaneousVerificationGuessesAreAllCounted() throws Exception {
        var user = account(false);

        guessAtOnce(() -> {
            var guess = new VerifyUserDto();
            guess.setEmail(user.getEmail());
            guess.setVerificationCode("222222");
            guess.setPassword("password");
            authentication.verifyUser(guess, DeviceContext.unknown());
        });

        assertThat(jdbc.queryForObject("select verification_code is null from users where id = ?",
                Boolean.class, user.getId()))
                .as("concurrent guesses overwrote each other's count, so more guesses than the limit were tried")
                .isTrue();
    }

    private static void guessAtOnce(Runnable guess) throws Exception {
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(GUESSES);
        List<Future<?>> guesses = new ArrayList<>();
        try {
            for (int i = 0; i < GUESSES; i++) {
                guesses.add(pool.submit(() -> {
                    start.await();
                    try {
                        guess.run();
                    } catch (GlobalException ignored) {
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> attempt : guesses) {
                attempt.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
