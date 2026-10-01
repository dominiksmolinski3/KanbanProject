package pl.myproject.kanbanproject2.config.security.ratelimit;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import redis.clients.authentication.core.IdentityProvider;
import redis.clients.authentication.core.SimpleToken;
import redis.clients.authentication.core.Token;

import java.time.Clock;
import java.util.Map;

final class RedisEntraIdentityProvider implements IdentityProvider {

    static final String SCOPE = "https://redis.azure.com/.default";

    private final TokenCredential credential;
    private final String username;
    private final Clock clock;

    RedisEntraIdentityProvider(TokenCredential credential, String username, Clock clock) {
        this.credential = credential;
        this.username = username;
        this.clock = clock;
    }

    @Override
    public Token requestToken() {
        AccessToken token = credential.getTokenSync(new TokenRequestContext().addScopes(SCOPE));
        return new SimpleToken(username, token.getToken(),
                token.getExpiresAt().toInstant().toEpochMilli(), clock.millis(), Map.of());
    }
}
