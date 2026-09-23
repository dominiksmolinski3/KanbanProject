package pl.myproject.kanbanproject2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.storage")
public record BlobStorageProperties(

        String endpoint,

        String connectionString,

        @DefaultValue("task-attachments") String container,

        String identityClientId,

        @DefaultValue("8") int maxConcurrentTransfers,

        @DefaultValue("1") int replicaCountHint,

        @DefaultValue("500") long maxAttachmentsPerBoard,

        @DefaultValue("1073741824") long maxTotalBytesPerBoard) {
    public boolean isConfigured() {
        return StringUtils.hasText(endpoint) || StringUtils.hasText(connectionString);
    }
}
