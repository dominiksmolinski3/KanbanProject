package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

/**
 * Builds the connection the rate limiter's Redis lives behind, from {@link AuthRateLimitProperties}
 * rather than Spring Boot's own {@code spring.data.redis.*} - the deployment's other secrets already
 * arrive as {@code security.*}/{@code app.*} properties bound the same way, and a second
 * configuration surface for the one thing that happens to use Redis would just be a second place to
 * look. Spring Boot's {@code RedisAutoConfiguration} takes it from here: with a
 * {@link org.springframework.data.redis.connection.RedisConnectionFactory} bean already in the
 * context, it builds the {@code StringRedisTemplate} {@link AuthRateLimiter} is wired with rather
 * than one of its own.
 *
 * <p>Jedis, not the starter's default Lettuce - see the exclusion in the POM. The pool this factory
 * creates does not open a socket until the first command runs, so a fresh clone with no Redis
 * reachable still starts; the first request against a limited path is where that would surface, and
 * {@link RedisEscalationStore} fails it open rather than 500ing.
 */
@Configuration
class RedisRateLimitConfiguration {

    @Bean
    JedisConnectionFactory rateLimitRedisConnectionFactory(AuthRateLimitProperties properties) {
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(properties.redisHost(), properties.redisPort());
        if (!properties.redisPassword().isBlank()) {
            standalone.setPassword(properties.redisPassword());
        }

        JedisClientConfiguration.JedisClientConfigurationBuilder client = JedisClientConfiguration.builder();
        if (properties.redisSsl()) {
            // Azure Cache for Redis terminates TLS on 6380; a plain build() targets the non-TLS
            // port a local docker-compose/CI Redis answers on instead.
            client.useSsl();
        }

        return new JedisConnectionFactory(standalone, client.build());
    }
}
