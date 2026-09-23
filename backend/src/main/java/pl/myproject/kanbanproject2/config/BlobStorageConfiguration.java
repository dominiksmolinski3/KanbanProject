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

@Slf4j
@Configuration
@EnableConfigurationProperties(BlobStorageProperties.class)
public class BlobStorageConfiguration {

    @Bean
    public BlobStore blobStore(BlobStorageProperties properties) {
        if (!properties.isConfigured()) {
            log.warn("Neither app.storage.endpoint nor app.storage.connection-string is set; "
                    + "task attachments and avatars will be refused rather than stored");
            return new DisabledBlobStore();
        }
        BlobServiceClient service = blobServiceClient(properties);
        BlobContainerClient container = service.getBlobContainerClient(properties.container());
        createContainerIfMissing(container);

        log.info("Task attachments and avatars are stored in the {} container of {}",
                properties.container(), service.getAccountUrl());
        return new AzureBlobStore(container);
    }

    private static final RequestRetryOptions RETRY_OPTIONS = new RequestRetryOptions(
            RetryPolicyType.EXPONENTIAL, 3, 30, 500L, 2000L, null);

    static BlobServiceClient blobServiceClient(BlobStorageProperties properties) {
        var builder = new BlobServiceClientBuilder().retryOptions(RETRY_OPTIONS);
        if (StringUtils.hasText(properties.connectionString())) {
            return builder.connectionString(properties.connectionString()).buildClient();
        }
        return builder.endpoint(properties.endpoint()).credential(credential(properties)).buildClient();
    }

    static TokenCredential credential(BlobStorageProperties properties) {
        if (StringUtils.hasText(properties.identityClientId())) {
            return new ManagedIdentityCredentialBuilder()
                    .clientId(properties.identityClientId())
                    .build();
        }
        return new DefaultAzureCredentialBuilder().build();
    }

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
