package pl.myproject.kanbanproject2.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.config.security.RefreshTokenService;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.ActiveDeviceMapper;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
import pl.myproject.kanbanproject2.user.auth.RefreshTokenRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig({DatabaseTestConfiguration.class, RefreshTokenRotationDatabaseTest.Beans.class})
class RefreshTokenRotationDatabaseTest {

    @Configuration
    static class Beans {
        @Bean
        ActiveDeviceMapper activeDeviceMapper() {
            return new ActiveDeviceMapper();
        }

        @Bean
        RefreshTokenService refreshTokenService(RefreshTokenRepository tokens, ActiveDeviceMapper mapper) {
            return new RefreshTokenService(tokens, mapper, 2_592_000_000L, 7_776_000_000L);
        }
    }

    @Autowired
    private RefreshTokenService service;

    @Autowired
    private UserRepository users;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private User account() {
        return transactions.execute(status -> {
            var user = new User("someone", "rotation-" + UUID.randomUUID() + "@example.test", "hashed");
            user.setEnabled(true);
            return users.save(user);
        });
    }

    private int liveSessions(User user) {
        return jdbc.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class, user.getId());
    }

    @Test
    @DisplayName("replaying a spent token withdraws every session, and the withdrawal survives the refusal")
    void reuseWithdrawsEverythingForGood() {
        var user = account();
        var first = service.issue(user, new DeviceContext("203.0.113.1", "device-a"));
        service.issue(user, new DeviceContext("203.0.113.2", "device-b"));
        service.rotate(first.token(), DeviceContext.unknown());
        assertThat(liveSessions(user)).isEqualTo(2);

        assertThatThrownBy(() -> service.rotate(first.token(), DeviceContext.unknown()))
                .isInstanceOf(GlobalException.class);

        assertThat(liveSessions(user))
                .as("the refusal rolled the withdrawal back, so a thief's chain lives on")
                .isZero();
        assertThat(jdbc.queryForObject("select sessions_revoked_at is not null from users where id = ?",
                Boolean.class, user.getId()))
                .as("access tokens issued before the reuse would keep working until they expire")
                .isTrue();
    }

    @Test
    @DisplayName("two rotations of one token at the same moment cannot both succeed and fork the session")
    void simultaneousRotationsAreSerialised() throws Exception {
        var user = account();
        var token = service.issue(user, DeviceContext.unknown()).token();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.rotate(token, DeviceContext.unknown());
                        return true;
                    } catch (GlobalException refused) {
                        return false;
                    }
                }));
            }
            start.countDown();
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> attempt : attempts) {
                outcomes.add(attempt.get(30, TimeUnit.SECONDS));
            }

            assertThat(Collections.frequency(outcomes, true))
                    .as("both rotations went through, so one token became two live chains")
                    .isEqualTo(1);
            assertThat(liveSessions(user))
                    .as("the second presentation is reuse, which withdraws the chain the first one started")
                    .isZero();
        } finally {
            pool.shutdownNow();
        }
    }
}
