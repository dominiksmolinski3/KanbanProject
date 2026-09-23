package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

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
            client.useSsl();
        }

        return new JedisConnectionFactory(standalone, client.build());
    }
}
