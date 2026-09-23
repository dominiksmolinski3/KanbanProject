package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RedisEscalationStoreIntegrationTest {
    private static final EscalationStore.Limit CREDENTIALS_IP = new EscalationStore.Limit(
            4, Duration.ofSeconds(15).toMillis(), Duration.ofMinutes(5).toMillis(), Duration.ofMinutes(15).toMillis());

    private JedisConnectionFactory connectionFactory;
    private RedisEscalationStore store;
    private String key;
    private long now;

    @BeforeEach
    void setUp() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        connectionFactory = new JedisConnectionFactory(new RedisStandaloneConfiguration(host, port));
        connectionFactory.afterPropertiesSet();
        store = new RedisEscalationStore(new StringRedisTemplate(connectionFactory));
        key = "test:auth-rate-limit:" + UUID.randomUUID();
        now = 1_700_000_000_000L;
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("a key is allowed through its burst and refused after it")
    void refusesOnceTheBurstIsSpent() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(store.attempt(key, CREDENTIALS_IP, now).allowed()).as("attempt %d", attempt).isTrue();
        }

        assertThat(store.attempt(key, CREDENTIALS_IP, now).allowed()).isFalse();
    }

    @Test
    @DisplayName("the wait doubles each time it is spent, and stops at the ceiling")
    void theWaitDoublesUpToTheCeiling() {
        for (int attempt = 0; attempt < 4; attempt++) {
            store.attempt(key, CREDENTIALS_IP, now);
        }

        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(15);
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(30);
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(60);
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(120);
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(240);
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(300);
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(300);
    }

    @Test
    @DisplayName("a refusal reports how long is left, and hammering neither extends it nor shortens it")
    void reportsARetryDelayThatDoesNotMoveUnderPressure() {
        for (int attempt = 0; attempt < 4; attempt++) {
            store.attempt(key, CREDENTIALS_IP, now);
        }

        AuthRateLimitDecision first = store.attempt(key, CREDENTIALS_IP, now);
        now += Duration.ofSeconds(5).toMillis();
        AuthRateLimitDecision afterTenMoreTries = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            afterTenMoreTries = store.attempt(key, CREDENTIALS_IP, now);
        }

        assertThat(first.retryAfterSeconds()).isEqualTo(15);
        assertThat(afterTenMoreTries).isNotNull();
        assertThat(afterTenMoreTries.allowed()).isFalse();
        assertThat(afterTenMoreTries.retryAfterSeconds()).isEqualTo(10);
    }

    @Test
    @DisplayName("a whole quiet window forgives the escalation and hands the burst back")
    void aQuietWindowForgivesTheKey() {
        for (int attempt = 0; attempt < 6; attempt++) {
            store.attempt(key, CREDENTIALS_IP, now);
        }
        assertThat(store.attempt(key, CREDENTIALS_IP, now).allowed()).isFalse();

        now += Duration.ofMinutes(15).toMillis();

        int allowed = 0;
        while (store.attempt(key, CREDENTIALS_IP, now).allowed()) {
            allowed++;
            if (allowed > 100) {
                throw new IllegalStateException("the store never refused");
            }
        }
        assertThat(allowed).isEqualTo(4);
    }

    @Test
    @DisplayName("quiet means quiet: a key kept warm by refused attempts is never forgiven")
    void hammeringKeepsTheEscalationAlive() {
        for (int attempt = 0; attempt < 5; attempt++) {
            store.attempt(key, CREDENTIALS_IP, now);
        }

        for (int minute = 0; minute < 20; minute++) {
            now += Duration.ofMinutes(1).toMillis();
            store.attempt(key, CREDENTIALS_IP, now);
        }

        assertThat(store.attempt(key, CREDENTIALS_IP, now).allowed()).isFalse();
    }

    @Test
    @DisplayName("exhausting one key leaves every other key untouched")
    void keysAreIndependent() {
        for (int attempt = 0; attempt < 4; attempt++) {
            store.attempt(key, CREDENTIALS_IP, now);
        }

        assertThat(store.attempt(key, CREDENTIALS_IP, now).allowed()).isFalse();
        assertThat(store.attempt(key + ":other", CREDENTIALS_IP, now).allowed()).isTrue();
    }

    private long waitAfterSittingOutTheCooldown() {
        AuthRateLimitDecision refused = store.attempt(key, CREDENTIALS_IP, now);
        assertThat(refused.allowed()).isFalse();
        now += Duration.ofSeconds(refused.retryAfterSeconds()).toMillis();
        assertThat(store.attempt(key, CREDENTIALS_IP, now).allowed()).isTrue();
        return refused.retryAfterSeconds();
    }
}
