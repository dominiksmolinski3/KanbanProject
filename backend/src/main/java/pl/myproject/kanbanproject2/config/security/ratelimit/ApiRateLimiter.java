package pl.myproject.kanbanproject2.config.security.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * How often one signed-in account may call the API: a token bucket per account, kept in the same
 * Redis the auth limiter uses, so the limit is fleet-wide - five replicas do not give an account five
 * buckets - and follows the account across addresses, which the edge's per-IP limit cannot.
 *
 * <p>Keyed on the account rather than the address on purpose. The edge already limits by address
 * and is what stops a flood that never authenticates; this is for the one that does, and an office
 * of twenty people behind one NAT address must not share one person's allowance.
 *
 * <p><b>It fails open</b>, like {@link RedisEscalationStore}: the limit is defence against abuse, not
 * a control anything else relies on, so Redis being unreachable costs the limit for the length of the
 * outage rather than every signed-in request.
 */
@Slf4j
@Component
public class ApiRateLimiter {

    static final String REFUSED_COUNTER = "kanban.api.ratelimit.refused";

    private static final RedisScript<List> SCRIPT = loadScript();

    private final ApiRateLimitProperties properties;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Counter refused;

    @Autowired
    public ApiRateLimiter(ApiRateLimitProperties properties, StringRedisTemplate redis, MeterRegistry registry) {
        this(properties, redis, Clock.systemUTC(), registry);
    }

    ApiRateLimiter(ApiRateLimitProperties properties, StringRedisTemplate redis, Clock clock, MeterRegistry registry) {
        // Refused at startup, like AuthRateLimitProperties' bad values: a rate of zero would divide
        // by zero in the script and a burst of zero would refuse every signed-in call.
        if (properties.perSecond() < 1) {
            throw new IllegalArgumentException("security.api-rate-limit.per-second must be at least 1");
        }
        if (properties.burst() < 1) {
            throw new IllegalArgumentException("security.api-rate-limit.burst must be at least 1");
        }
        this.properties = properties;
        this.redis = redis;
        this.clock = clock;
        this.refused = registry.counter(REFUSED_COUNTER);
    }

    /** Takes one call from the account's bucket, or says how long until it may try again. */
    @SuppressWarnings("unchecked")
    public AuthRateLimitDecision attempt(Integer accountId) {
        try {
            List<Long> result = redis.execute(SCRIPT, List.of("api-rate-limit:account:" + accountId),
                    String.valueOf(clock.millis()),
                    String.valueOf(properties.perSecond()),
                    String.valueOf(properties.burst()));
            if (result == null || result.size() != 2) {
                throw new IllegalStateException("api-rate-limit.lua returned " + result);
            }
            if (result.get(0) == 1L) {
                return AuthRateLimitDecision.allow();
            }
            refused.increment();
            return AuthRateLimitDecision.refuse(Duration.ofMillis(result.get(1)));
        } catch (DataAccessException | IllegalStateException e) {
            log.warn("API rate limiter could not use Redis, allowing the call: {}", e.getMessage());
            return AuthRateLimitDecision.allow();
        }
    }

    private static RedisScript<List> loadScript() {
        try {
            String script = StreamUtils.copyToString(
                    new ClassPathResource("redis/api-rate-limit.lua").getInputStream(), StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(script, List.class);
        } catch (IOException e) {
            throw new UncheckedIOException("redis/api-rate-limit.lua is missing from the classpath", e);
        }
    }
}
