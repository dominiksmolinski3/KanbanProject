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
 * Builds the attachment store from {@link BlobStorageProperties}. Same shape as
 * {@link EmailConfiguration}: an unconfigured deployment starts and refuses uploads rather than
 * failing to boot, and {@code StorageHealthIndicator} reports that state on
 * {@code /actuator/health}.
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
     * Retries, bounded. The SDK's default (four attempts, doubling) is forty-odd seconds before an
     * unreachable account is admitted to be unreachable, spent inside every upload request; three
     * tries a second or two apart keeps a transient failure recoverable without making a real
     * outage slow to report.
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
     * Naming it matters on Container Apps: an app may carry several assigned identities, and
     * {@code DefaultAzureCredential} cannot guess which one the storage role was granted to.
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
     * Terraform provisions the storage account; the application provisions its own container,
     * idempotently, because creating one is a data-plane call and keeping Terraform off the data
     * plane is what lets the account set {@code shared_access_key_enabled = false}. A failure here
     * is logged and not thrown: an unreachable storage account should cost the deployment its
     * uploads, not its ability to serve the board at all.
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
