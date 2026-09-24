package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

import java.time.Duration;

@Configuration
class RedisRateLimitConfiguration {

    // Both limiters fail open, so this bounds what an unreachable Redis adds to every request.
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(250);
    static final Duration READ_TIMEOUT = Duration.ofMillis(500);

    @Bean
    JedisConnectionFactory rateLimitRedisConnectionFactory(AuthRateLimitProperties properties) {
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(properties.redisHost(), properties.redisPort());
        if (!properties.redisPassword().isBlank()) {
            standalone.setPassword(properties.redisPassword());
        }

        JedisClientConfiguration.JedisClientConfigurationBuilder client = JedisClientConfiguration.builder()
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT);
        if (properties.redisSsl()) {
            client.useSsl();
        }

        return new JedisConnectionFactory(standalone, client.build());
    }
}
