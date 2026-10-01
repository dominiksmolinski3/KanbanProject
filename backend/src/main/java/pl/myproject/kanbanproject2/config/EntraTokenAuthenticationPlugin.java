package pl.myproject.kanbanproject2.config;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import org.postgresql.plugin.AuthenticationPlugin;
import org.postgresql.plugin.AuthenticationRequestType;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public final class EntraTokenAuthenticationPlugin implements AuthenticationPlugin {
    public static final String CLIENT_ID_PROPERTY = "azureClientId";
    static final String SCOPE = "https://ossrdbms-aad.database.windows.net/.default";

    // pgjdbc instantiates the plugin on every connect; the credential holds the token cache, so it must outlive it.
    private static final Map<String, TokenCredential> CREDENTIALS = new ConcurrentHashMap<>();

    private final TokenCredential credential;

    public EntraTokenAuthenticationPlugin(Properties info) {
        this(CREDENTIALS.computeIfAbsent(info.getProperty(CLIENT_ID_PROPERTY, ""),
                EntraTokenAuthenticationPlugin::credentialFor));
    }

    EntraTokenAuthenticationPlugin(TokenCredential credential) {
        this.credential = credential;
    }

    @Override
    public char[] getPassword(AuthenticationRequestType type) throws PSQLException {
        try {
            return credential.getTokenSync(new TokenRequestContext().addScopes(SCOPE)).getToken().toCharArray();
        } catch (RuntimeException e) {
            throw new PSQLException("Could not get an Entra token for the database: " + e.getMessage(),
                    PSQLState.CONNECTION_REJECTED, e);
        }
    }

    private static TokenCredential credentialFor(String clientId) {
        if (clientId.isBlank()) {
            return new DefaultAzureCredentialBuilder().build();
        }
        return new ManagedIdentityCredentialBuilder().clientId(clientId).build();
    }
}
