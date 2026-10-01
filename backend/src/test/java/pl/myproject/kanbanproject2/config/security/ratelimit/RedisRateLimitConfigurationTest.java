package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitTestSupport.properties;

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

    @Test
    @DisplayName("an unreachable Redis costs a request a fraction of a second, not Jedis's two-second default")
    void timeoutsAreShorterThanTheJedisDefault() {
        JedisConnectionFactory factory = configuration.rateLimitRedisConnectionFactory(withRedis("localhost", 6380, "s3cret", true));

        assertThat(factory.getClientConfiguration().getConnectTimeout()).isEqualTo(RedisRateLimitConfiguration.CONNECT_TIMEOUT);
        assertThat(factory.getClientConfiguration().getReadTimeout()).isEqualTo(RedisRateLimitConfiguration.READ_TIMEOUT);
        assertThat(RedisRateLimitConfiguration.CONNECT_TIMEOUT).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("with an Entra client id the factory carries a token manager and no password")
    void entraLogsInWithATokenManager() {
        JedisConnectionFactory factory = configuration.rateLimitRedisConnectionFactory(
                withRedis("redis-kanban-dev.polandcentral.redis.azure.net", 10000, "", true, "client-id", "object-id"));

        assertThat(factory.getPassword()).isNull();
        assertThat(factory.getClientConfiguration().getClientConfigCustomizer()).isPresent();
    }

    @Test
    @DisplayName("without one the factory carries no token manager, as docker-compose and CI expect")
    void noEntraMeansNoTokenManager() {
        JedisConnectionFactory factory = configuration.rateLimitRedisConnectionFactory(withRedis("localhost", 6379, "", false));

        assertThat(factory.getClientConfiguration().getClientConfigCustomizer()).isEmpty();
    }

    @Test
    @DisplayName("an Entra login needs the identity's object id as its user, and is never mixed with a password")
    void entraIsValidatedAtStartup() {
        assertThatThrownBy(() -> withRedis("h", 10000, "", true, "client-id", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redis-username");
        assertThatThrownBy(() -> withRedis("h", 10000, "s3cret", true, "client-id", "object-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("set one");
    }

    private static AuthRateLimitProperties withRedis(String host, int port, String password, boolean ssl) {
        return withRedis(host, port, password, ssl, "", "");
    }

    private static AuthRateLimitProperties withRedis(String host, int port, String password, boolean ssl,
                                                     String entraClientId, String username) {
        AuthRateLimitProperties defaults = properties();
        return new AuthRateLimitProperties(
                defaults.enabled(), defaults.trustedProxyCount(), defaults.maxTrackedKeys(),
                defaults.credentialAttemptsPerIp(), defaults.credentialAttemptsPerAccount(),
                defaults.credentialBaseCooldown(), defaults.credentialMaxCooldown(), defaults.credentialWindow(),
                defaults.emailRequestsPerIp(), defaults.emailRequestsPerAccount(),
                defaults.emailBaseCooldown(), defaults.emailMaxCooldown(), defaults.emailWindow(),
                host, port, password, ssl, entraClientId, username);
    }
}
