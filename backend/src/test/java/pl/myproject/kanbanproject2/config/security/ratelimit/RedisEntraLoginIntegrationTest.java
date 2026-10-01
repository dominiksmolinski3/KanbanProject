package pl.myproject.kanbanproject2.config.security.ratelimit;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitTestSupport.properties;

class RedisEntraLoginIntegrationTest {

    private final String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
    private final int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
    private final String user = "entra-" + UUID.randomUUID();
    private final String token = UUID.randomUUID().toString();

    private JedisConnectionFactory admin;
    private JedisConnectionFactory factory;

    @BeforeEach
    void createTheUser() {
        admin = new JedisConnectionFactory(new RedisStandaloneConfiguration(host, port));
        admin.afterPropertiesSet();
        admin.start();
        try (var connection = admin.getConnection()) {
            connection.execute("ACL", bytes("SETUSER"), bytes(user), bytes("on"), bytes(">" + token),
                    bytes("~*"), bytes("+@all"));
        }
    }

    @AfterEach
    void dropTheUser() {
        if (factory != null) {
            factory.destroy();
        }
        try (var connection = admin.getConnection()) {
            connection.execute("ACL", bytes("DELUSER"), bytes(user));
        }
        admin.destroy();
    }

    @Test
    @DisplayName("the factory logs in with the token as the configured user, not as the default user")
    void logsInWithTheToken() {
        TokenCredential credential = request -> Mono.just(new AccessToken(token, OffsetDateTime.now().plusHours(1)));
        AuthRateLimitProperties defaults = properties();
        factory = RedisRateLimitConfiguration.connectionFactory(
                new AuthRateLimitProperties(
                        defaults.enabled(), defaults.trustedProxyCount(), defaults.maxTrackedKeys(),
                        defaults.credentialAttemptsPerIp(), defaults.credentialAttemptsPerAccount(),
                        defaults.credentialBaseCooldown(), defaults.credentialMaxCooldown(), defaults.credentialWindow(),
                        defaults.emailRequestsPerIp(), defaults.emailRequestsPerAccount(),
                        defaults.emailBaseCooldown(), defaults.emailMaxCooldown(), defaults.emailWindow(),
                        host, port, "", false, "client-id", user),
                RedisRateLimitConfiguration.authXManager(credential, user));
        factory.afterPropertiesSet();
        factory.start();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();

        Object whoami = redis.execute((RedisCallback<Object>)
                connection -> connection.execute("ACL", bytes("WHOAMI")));

        assertThat(new String((byte[]) whoami, StandardCharsets.UTF_8)).isEqualTo(user);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
