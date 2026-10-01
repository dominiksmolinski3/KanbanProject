package pl.myproject.kanbanproject2.config.security.ratelimit;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.authentication.core.Token;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisEntraIdentityProviderTest {

    @Test
    @DisplayName("asks Entra for a Redis token and logs in as the identity's object id with it")
    void turnsAnEntraTokenIntoARedisLogin() {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        OffsetDateTime expires = OffsetDateTime.of(2026, 10, 1, 13, 0, 0, 0, ZoneOffset.UTC);
        TokenCredential credential = mock(TokenCredential.class);
        var request = ArgumentCaptor.forClass(TokenRequestContext.class);
        when(credential.getTokenSync(request.capture())).thenReturn(new AccessToken("eyJ.token", expires));

        Token token = new RedisEntraIdentityProvider(credential, "object-id", Clock.fixed(now, ZoneOffset.UTC))
                .requestToken();

        verify(credential).getTokenSync(request.getValue());
        assertThat(request.getValue().getScopes()).containsExactly(RedisEntraIdentityProvider.SCOPE);
        assertThat(token.getUser()).isEqualTo("object-id");
        assertThat(token.getValue()).isEqualTo("eyJ.token");
        assertThat(token.getExpiresAt()).isEqualTo(expires.toInstant().toEpochMilli());
        assertThat(token.getReceivedAt()).isEqualTo(now.toEpochMilli());
    }
}
