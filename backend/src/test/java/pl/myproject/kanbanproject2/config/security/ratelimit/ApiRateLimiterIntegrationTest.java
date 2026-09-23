package pl.myproject.kanbanproject2.config.security.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real {@code redis/api-rate-limit.lua} against a real Redis, on the same terms as
 * {@code RedisEscalationStoreIntegrationTest}: the CI backend job runs one as a service container,
 * and {@code REDIS_HOST}/{@code REDIS_PORT} point elsewhere locally. The clock is the test's, so a
 * refill is asserted without the test ever sleeping.
 */
class ApiRateLimiterIntegrationTest {

    private static final ApiRateLimitProperties LIMIT = new ApiRateLimitProperties(true, 20, 100);

    private JedisConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private SimpleMeterRegistry registry;
    private Integer account;

    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    };

    @BeforeEach
    void setUp() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        connectionFactory = new JedisConnectionFactory(new RedisStandaloneConfiguration(host, port));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        registry = new SimpleMeterRegistry();
        // A fresh account per test, far above any id a real run would create.
        account = 1_000_000 + ThreadLocalRandom.current().nextInt(1_000_000);
    }

    @AfterEach
    void tearDown() {
        redis.delete("api-rate-limit:account:" + account);
        connectionFactory.destroy();
    }

    private ApiRateLimiter replica() {
        return new ApiRateLimiter(LIMIT, redis, clock, registry);
    }

    @Test
    @DisplayName("the burst is allowed and the call after it is refused, with the wait for one token")
    void burstThenRefusal() {
        var limiter = replica();
        for (int call = 0; call < 100; call++) {
            assertThat(limiter.attempt(account).allowed()).as("call %d of the burst", call + 1).isTrue();
        }

        var refused = limiter.attempt(account);

        assertThat(refused.allowed()).isFalse();
        // One token at twenty a second is fifty milliseconds away, which Retry-After rounds to a second.
        assertThat(refused.retryAfter().toMillis()).isEqualTo(50);
        assertThat(refused.retryAfterSeconds()).isEqualTo(1);
        assertThat(registry.counter(ApiRateLimiter.REFUSED_COUNTER).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the bucket refills at the configured rate, and never past the burst")
    void refills() {
        var limiter = replica();
        for (int call = 0; call < 100; call++) {
            limiter.attempt(account);
        }

        now.addAndGet(1_000);
        int allowed = 0;
        while (limiter.attempt(account).allowed()) {
            allowed++;
        }
        assertThat(allowed).as("one second at twenty a second").isEqualTo(20);

        now.addAndGet(3_600_000);
        allowed = 0;
        while (limiter.attempt(account).allowed()) {
            allowed++;
        }
        assertThat(allowed).as("an hour idle refills to the burst, not to 72 000").isEqualTo(100);
    }

    @Test
    @DisplayName("two replicas spend one bucket, not one each")
    void fleetWide() {
        var first = replica();
        var second = replica();
        for (int call = 0; call < 50; call++) {
            first.attempt(account);
            second.attempt(account);
        }

        assertThat(first.attempt(account).allowed()).isFalse();
        assertThat(second.attempt(account).allowed()).isFalse();
    }

    @Test
    @DisplayName("a refused call takes nothing, so hammering does not lengthen the wait")
    void refusalsAreFree() {
        var limiter = replica();
        for (int call = 0; call < 100; call++) {
            limiter.attempt(account);
        }
        for (int call = 0; call < 1_000; call++) {
            limiter.attempt(account);
        }

        now.addAndGet(50);
        assertThat(limiter.attempt(account).allowed()).isTrue();
    }

    @Test
    @DisplayName("an idle account's bucket expires rather than staying in Redis forever")
    void expires() {
        replica().attempt(account);

        Long ttl = redis.getExpire("api-rate-limit:account:" + account);
        // Full after 100 / 20 = 5 s, plus a second of margin.
        assertThat(ttl).isBetween(1L, 6L);
    }
}
