package pl.myproject.kanbanproject2.config.security.ratelimit;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.authentication.core.TokenAuthConfig;
import redis.clients.jedis.authentication.AuthXManager;

import java.time.Clock;
import java.time.Duration;

@Configuration
class RedisRateLimitConfiguration {

    // Both limiters fail open, so this bounds what an unreachable Redis adds to every request.
    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(250);
    static final Duration READ_TIMEOUT = Duration.ofMillis(500);

    @Bean
    JedisConnectionFactory rateLimitRedisConnectionFactory(AuthRateLimitProperties properties) {
        if (!properties.redisEntraEnabled()) {
            return connectionFactory(properties, null);
        }
        TokenCredential credential =
                new ManagedIdentityCredentialBuilder().clientId(properties.redisEntraClientId()).build();
        return connectionFactory(properties, authXManager(credential, properties.redisUsername()));
    }

    static JedisConnectionFactory connectionFactory(AuthRateLimitProperties properties, AuthXManager authXManager) {
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
        if (authXManager == null) {
            return new JedisConnectionFactory(standalone, client.build());
        }

        client.customize(config -> config.authXManager(authXManager));
        return new JedisConnectionFactory(standalone, client.build()) {
            @Override
            public void destroy() {
                super.destroy();
                authXManager.stop();
            }
        };
    }

    static AuthXManager authXManager(TokenCredential credential, String username) {
        var provider = new RedisEntraIdentityProvider(credential, username, Clock.systemUTC());
        return new AuthXManager(TokenAuthConfig.builder()
                .expirationRefreshRatio(0.75f)
                .lowerRefreshBoundMillis((int) Duration.ofMinutes(2).toMillis())
                .tokenRequestExecTimeoutInMs((int) Duration.ofSeconds(10).toMillis())
                .maxAttemptsToRetry(5)
                .delayInMsToRetry((int) Duration.ofSeconds(1).toMillis())
                .identityProviderConfig(() -> provider)
                .build());
    }
}
