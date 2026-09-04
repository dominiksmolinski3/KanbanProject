package pl.myproject.kanbanproject2.config;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import pl.myproject.kanbanproject2.storage.AzureBlobStore;
import pl.myproject.kanbanproject2.storage.BlobStore;
import pl.myproject.kanbanproject2.storage.DisabledBlobStore;

/**
 * Builds the attachment store from {@link BlobStorageProperties}.
 *
 * <p>The same shape as {@link EmailConfiguration}, and for the same reasons: one place decides
 * which implementation the application gets, nothing downstream knows whether storage is
 * configured, and an unconfigured deployment starts rather than refusing to. The trade is the same
 * too - a deployment that forgets the endpoint boots and then refuses every upload - so the startup
 * warning below names the properties, and {@code StorageHealthIndicator} says so on
 * {@code /actuator/health} for as long as it stays that way.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(BlobStorageProperties.class)
public class BlobStorageConfiguration {

    @Bean
    public BlobStore blobStore(BlobStorageProperties properties) {
        if (!properties.isConfigured()) {
            log.warn("Neither app.storage.endpoint nor app.storage.connection-string is set; "
                    + "task attachments will be refused rather than stored");
            return new DisabledBlobStore();
        }
        BlobServiceClient service = blobServiceClient(properties);
        BlobContainerClient container = service.getBlobContainerClient(properties.container());
        createContainerIfMissing(container);

        log.info("Task attachments are stored in the {} container of {}",
                properties.container(), service.getAccountUrl());
        return new AzureBlobStore(container);
    }

    /**
     * Retries, bounded.
     *
     * <p>The SDK's default is four attempts four seconds apart and doubling, which is forty-odd
     * seconds before an unreachable account is admitted to be unreachable - spent inside bean
     * creation on the first start, and inside a request for every upload after it. Three tries with
     * a second or two between them keeps a transient failure recoverable and a real outage quick to
     * report. The per-try timeout is generous because the biggest thing that goes through this
     * client is a ten-megabyte upload.
     */
    private static final RequestRetryOptions RETRY_OPTIONS = new RequestRetryOptions(
            RetryPolicyType.EXPONENTIAL, 3, 30, 500L, 2000L, null);

    static BlobServiceClient blobServiceClient(BlobStorageProperties properties) {
        var builder = new BlobServiceClientBuilder().retryOptions(RETRY_OPTIONS);
        if (StringUtils.hasText(properties.connectionString())) {
            return builder.connectionString(properties.connectionString()).buildClient();
        }
        return builder.endpoint(properties.endpoint()).credential(credential(properties)).buildClient();
    }

    /**
     * The user-assigned identity when one is named, and whatever the environment offers otherwise.
     *
     * <p>Naming it matters on Container Apps: an app may carry several assigned identities, and
     * {@code DefaultAzureCredential} cannot guess which of them the storage role was granted to. It
     * is the fallback rather than the default because it is what picks up a developer's Azure CLI
     * login, which is how you point a laptop at a real account without an Azurite container.
     */
    static TokenCredential credential(BlobStorageProperties properties) {
        if (StringUtils.hasText(properties.identityClientId())) {
            return new ManagedIdentityCredentialBuilder()
                    .clientId(properties.identityClientId())
                    .build();
        }
        return new DefaultAzureCredentialBuilder().build();
    }

    /**
     * Terraform provisions the storage account; the application provisions its own container.
     *
     * <p>That split is what lets the account set {@code shared_access_key_enabled = false}. Creating
     * a container from Terraform is a data-plane call, which would mean either leaving the account
     * key enabled or granting whoever runs {@code apply} a blob data role - and the whole point of
     * the delegation-key arrangement is that no key exists. The identity that writes blobs can make
     * the container it writes them into, and doing so is idempotent.
     *
     * <p>A failure here is logged and not thrown. The container almost always already exists, this
     * runs while the context is starting, and an unreachable storage account should cost the
     * deployment its uploads rather than its ability to serve the board at all.
     */
    private static void createContainerIfMissing(BlobContainerClient container) {
        try {
            if (Boolean.TRUE.equals(container.createIfNotExists())) {
                log.info("Created the {} blob container", container.getBlobContainerName());
            }
        } catch (RuntimeException e) {
            log.warn("Could not confirm the {} blob container exists; uploads will fail until it does: {}",
                    container.getBlobContainerName(), e.getMessage());
        }
    }
}
