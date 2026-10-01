package pl.myproject.kanbanproject2.config;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.postgresql.plugin.AuthenticationRequestType;
import org.postgresql.util.PSQLException;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntraTokenAuthenticationPluginTest {
    private static final Path API_APP = Path.of("..", "terraform", "modules", "api_app", "main.tf");
    private static final Pattern JDBC_URL = Pattern.compile("\"(jdbc:postgresql://[^\"]+)\"");

    private static final class RecordingCredential implements TokenCredential {
        final List<List<String>> scopes = new ArrayList<>();

        @Override
        public Mono<AccessToken> getToken(TokenRequestContext request) {
            return Mono.fromSupplier(() -> getTokenSync(request));
        }

        @Override
        public AccessToken getTokenSync(TokenRequestContext request) {
            scopes.add(request.getScopes());
            return new AccessToken("entra-token", OffsetDateTime.now().plusHours(1));
        }
    }

    @Test
    @DisplayName("the password is a token for the Postgres audience, whatever the server asks for")
    void answersWithAPostgresToken() throws PSQLException {
        var credential = new RecordingCredential();
        var plugin = new EntraTokenAuthenticationPlugin(credential);

        assertThat(plugin.getPassword(AuthenticationRequestType.CLEARTEXT_PASSWORD)).isEqualTo("entra-token".toCharArray());
        assertThat(plugin.getPassword(AuthenticationRequestType.SASL)).isEqualTo("entra-token".toCharArray());
        assertThat(credential.scopes).containsOnly(List.of(EntraTokenAuthenticationPlugin.SCOPE));
    }

    @Test
    @DisplayName("a token that cannot be had is a refused connection, which Hikari retries")
    void aFailedTokenIsAConnectionError() {
        TokenCredential failing = new TokenCredential() {
            @Override
            public Mono<AccessToken> getToken(TokenRequestContext request) {
                return Mono.error(new IllegalStateException("no identity endpoint"));
            }

            @Override
            public AccessToken getTokenSync(TokenRequestContext request) {
                throw new IllegalStateException("no identity endpoint");
            }
        };

        assertThatThrownBy(() -> new EntraTokenAuthenticationPlugin(failing).getPassword(AuthenticationRequestType.SASL))
                .isInstanceOf(PSQLException.class)
                .hasMessageContaining("no identity endpoint");
    }

    @Test
    @DisplayName("the URL the container app is given loads this plugin as the named identity")
    void theDeployedUrlNamesThisPlugin() throws IOException {
        Matcher match = JDBC_URL.matcher(Files.readString(API_APP));
        assertThat(match.find()).as("no JDBC URL in %s", API_APP).isTrue();
        String url = match.group(1).replaceAll("%s", "host").replaceAll("\\$\\{[^}]+}", "x");

        Properties parsed = Driver.parseURL(url, new Properties());

        assertThat(parsed).as("pgjdbc could not parse %s", url).isNotNull();
        assertThat(parsed.getProperty("authenticationPluginClassName"))
                .isEqualTo(EntraTokenAuthenticationPlugin.class.getName());
        assertThat(parsed).containsKey(EntraTokenAuthenticationPlugin.CLIENT_ID_PROPERTY);
        assertThat(parsed.getProperty("sslmode")).isEqualTo("verify-full");
        assertThat(parsed.getProperty("sslfactory")).isEqualTo("org.postgresql.ssl.DefaultJavaSSLFactory");
    }

    @Test
    @DisplayName("pgjdbc can build the plugin from the connection properties alone")
    void pgjdbcCanInstantiateIt() {
        var info = new Properties();
        info.setProperty(EntraTokenAuthenticationPlugin.CLIENT_ID_PROPERTY, "11111111-2222-3333-4444-555555555555");

        assertThat(new EntraTokenAuthenticationPlugin(info)).isNotNull();
    }
}
