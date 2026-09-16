package pl.myproject.kanbanproject2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * Where task attachments are kept, and how they are reached again. A deployment sets exactly one
 * of {@link #endpoint} (production: managed identity, no account key at all) or
 * {@link #connectionString} (local Azurite, whose key is a published constant). Neither set turns
 * attachments off rather than failing to boot - the same allowance {@link AcsMailProperties} makes
 * for mail, so a fresh clone and CI run without an Azure subscription.
 */
@ConfigurationProperties(prefix = "app.storage")
public record BlobStorageProperties(

        /*
         * The blob service endpoint - `https://<account>.blob.core.windows.net`. Reached with a
         * token rather than a key, so it is not a secret and is passed as a plain environment
         * variable rather than through Key Vault.
         */
        String endpoint,

        /*
         * The alternative to the endpoint, for a local Azurite container. It carries an account
         * key, which is why nothing in a real deployment sets this - a deployed account has no key
         * to put in one.
         */
        String connectionString,

        /*
         * The container attachments are written to. Created on first start if missing - Terraform
         * provisions the account but not the container, since keeping it off the data plane is
         * what lets the account refuse shared-key access.
         */
        @DefaultValue("task-attachments") String container,

        /*
         * The client id of the user-assigned managed identity to authenticate as. Empty falls back
         * to DefaultAzureCredential. Named explicitly rather than left to the SDK's own
         * AZURE_CLIENT_ID, because a variable nothing in this repo reads is one ConfigurationTest
         * reports as dead configuration.
         */
        String identityClientId,

        /*
         * How many uploads and downloads may stream through this application at once, each holding
         * a thread and a buffer for the transfer's duration. A per-JVM Semaphore, not a global one:
         * at api_max_replicas > 1 the true ceiling is this value times the replica count, so the
         * name reads "per replica" once a second API replica exists.
         */
        @DefaultValue("8") int maxConcurrentTransfers,

        /*
         * A per-board ceiling on how many attachments may exist at once, checked alongside
         * {@link #maxTotalBytesPerBoard} before a blob is written. 500 is fifty of the largest file
         * this feature allows.
         */
        @DefaultValue("500") long maxAttachmentsPerBoard,

        /*
         * A per-board ceiling on the combined size of every attachment, in bytes - one gibibyte,
         * a hundred files at the ten-megabyte per-file limit.
         */
        @DefaultValue("1073741824") long maxTotalBytesPerBoard) {

    /** Whether there is enough here to store anything at all. */
    public boolean isConfigured() {
        return StringUtils.hasText(endpoint) || StringUtils.hasText(connectionString);
    }
}
