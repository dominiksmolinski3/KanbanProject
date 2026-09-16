package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitTestSupport.properties;

/**
 * The connection factory bean is built by hand from {@link AuthRateLimitProperties} rather than
 * left to Spring Boot's own {@code spring.data.redis.*} binding, so nothing but this test checks
 * that the properties actually reach it.
 */
class RedisRateLimitConfigurationTest {

    private final RedisRateLimitConfiguration configuration = new RedisRateLimitConfiguration();

    @Test
    @DisplayName("host and port reach the connection factory")
    void bindsHostAndPort() {
        JedisConnectionFactory factory = configuration.rateLimitRedisConnectionFactory(withRedis("redis-host", 6380, "", false));

        assertThat(factory.getHostName()).isEqualTo("redis-host");
        assertThat(factory.getPort()).isEqualTo(6380);
    }

    @Test
    @DisplayName("a blank password leaves the connection unauthenticated, the local default")
    void aBlankPasswordMeansNoAuth() {
        JedisConnectionFactory factory = configuration.rateLimitRedisConnectionFactory(withRedis("localhost", 6379, "", false));

        assertThat(factory.getPassword()).isNull();
    }

    @Test
    @DisplayName("a configured password reaches the connection factory")
    void aConfiguredPasswordIsUsed() {
        JedisConnectionFactory factory = configuration.rateLimitRedisConnectionFactory(withRedis("localhost", 6380, "s3cret", true));

        assertThat(factory.getPassword()).isEqualTo("s3cret");
    }

    @Test
    @DisplayName("SSL is off by default, for the plain-text local Redis docker-compose and CI provide")
    void sslIsOffByDefault() {
        JedisConnectionFactory factory = configuration.rateLimitRedisConnectionFactory(withRedis("localhost", 6379, "", false));

        assertThat(factory.isUseSsl()).isFalse();
    }

    @Test
    @DisplayName("SSL is on when the deployment's Redis (TLS-only) asks for it")
    void sslIsOnWhenConfigured() {
        JedisConnectionFactory factory = configuration.rateLimitRedisConnectionFactory(withRedis("kanban-redis.privatelink.redis.cache.windows.net", 6380, "s3cret", true));

        assertThat(factory.isUseSsl()).isTrue();
    }

    private static AuthRateLimitProperties withRedis(String host, int port, String password, boolean ssl) {
        AuthRateLimitProperties defaults = properties();
        return new AuthRateLimitProperties(
                defaults.enabled(), defaults.trustedProxyCount(), defaults.maxTrackedKeys(),
                defaults.credentialAttemptsPerIp(), defaults.credentialAttemptsPerAccount(),
                defaults.credentialBaseCooldown(), defaults.credentialMaxCooldown(), defaults.credentialWindow(),
                defaults.emailRequestsPerIp(), defaults.emailRequestsPerAccount(),
                defaults.emailBaseCooldown(), defaults.emailMaxCooldown(), defaults.emailWindow(),
                host, port, password, ssl);
    }
}
