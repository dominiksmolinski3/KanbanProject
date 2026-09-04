package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.storage.AzureBlobStore;
import pl.myproject.kanbanproject2.storage.DisabledBlobStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which store a given configuration produces, decided without an Azure account.
 *
 * <p>Wiring is the part of this feature nothing else can check. The credential choice in particular
 * has no compiler on either side: a deployment that names a user-assigned identity and gets
 * {@code DefaultAzureCredential} anyway fails at the first upload with an authentication error that
 * says nothing about which identity it tried, and only on a container that is already running.
 *
 * <p>Everything here is local. The one case that reaches the network - the container check on
 * startup - is pointed at a closed port on purpose, because what is being asserted is that a
 * storage account it cannot reach costs the deployment its uploads and not its ability to start.
 */
class BlobStorageConfigurationTest {

    /**
     * Azurite's published development credentials, with the endpoint moved to a port nothing is
     * listening on. The key is a documented constant rather than a secret; the point of it here is
     * only that the SDK will parse the string.
     */
    private static final String UNREACHABLE_CONNECTION_STRING =
            "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
                    + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6"
                    + "tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://127.0.0.1:1/devstoreaccount1;";

    private final BlobStorageConfiguration configuration = new BlobStorageConfiguration();

    private static BlobStorageProperties properties(String endpoint, String connectionString,
                                                    String identityClientId) {
        return new BlobStorageProperties(endpoint, connectionString, "task-attachments",
                identityClientId);
    }

    @Test
    @DisplayName("no account configured is a disabled store, not a failure to start")
    void noAccountMeansDisabled() {
        var store = configuration.blobStore(properties("", "", ""));

        assertThat(store).isInstanceOf(DisabledBlobStore.class);
        assertThat(store.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("a connection string produces a real store, and an unreachable account does not stop the context")
    void aConnectionStringProducesAStore() {
        var store = configuration.blobStore(
                properties("", UNREACHABLE_CONNECTION_STRING, ""));

        assertThat(store).isInstanceOf(AzureBlobStore.class);
        assertThat(store.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("a named identity is the one authenticated as, not whatever the environment offers")
    void namesTheUserAssignedIdentity() {
        var credential = BlobStorageConfiguration.credential(
                properties("https://account.blob.core.windows.net", "", "11111111-2222-3333-4444-555555555555"));

        assertThat(credential.getClass().getSimpleName()).isEqualTo("ManagedIdentityCredential");
    }

    @Test
    @DisplayName("with no identity named it falls back to the ambient one, which is what an az login is")
    void fallsBackToTheAmbientCredential() {
        var credential = BlobStorageConfiguration.credential(
                properties("https://account.blob.core.windows.net", "", ""));

        assertThat(credential.getClass().getSimpleName()).isEqualTo("DefaultAzureCredential");
    }

    @Test
    @DisplayName("an endpoint is reached as itself, with no key in the picture")
    void buildsAnEndpointClient() {
        var client = BlobStorageConfiguration.blobServiceClient(
                properties("https://account.blob.core.windows.net", "", ""));

        assertThat(client.getAccountUrl()).startsWith("https://account.blob.core.windows.net");
    }

    @Test
    @DisplayName("a connection string is reached at the endpoint it names")
    void buildsAConnectionStringClient() {
        var client = BlobStorageConfiguration.blobServiceClient(
                properties("", UNREACHABLE_CONNECTION_STRING, ""));

        assertThat(client.getAccountUrl()).startsWith("http://127.0.0.1:1/devstoreaccount1");
    }
}
