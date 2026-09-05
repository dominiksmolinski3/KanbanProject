package pl.myproject.kanbanproject2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * Where task attachments are kept, and how they are reached again.
 *
 * <p>Two ways in, and a deployment sets exactly one of them. {@link #endpoint} with no key is the
 * production shape: the container app authenticates as its managed identity and the storage account
 * has shared-key authorization turned off, so there is no account key to leak. {@link
 * #connectionString} is local development against Azurite, whose key is a published constant and
 * not a secret at all.
 *
 * <p>Neither set turns attachments off - the application starts and refuses uploads, the same
 * allowance {@link AcsMailProperties} makes for mail, and for the same reason: a fresh clone and CI
 * should be able to run the whole thing without an Azure subscription.
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
         * The container attachments are written to. Created on first start if it is missing, which
         * is the reason Terraform provisions the account and not the container: creating it needs
         * data-plane rights the identity already has, and not having Terraform reach the data plane
         * is what lets the account refuse shared-key access.
         */
        @DefaultValue("task-attachments") String container,

        /*
         * The client id of the user-assigned managed identity to authenticate as. Empty falls back
         * to DefaultAzureCredential, which is what picks up an Azure CLI login when a developer
         * points this at a real account.
         *
         * Named explicitly rather than left to the SDK's own AZURE_CLIENT_ID, because a variable
         * the application never reads is one ConfigurationTest reports as dead configuration - and
         * it would be right to: nothing else in this repo would connect that name to this bean.
         */
        String identityClientId,

        /*
         * How many uploads and downloads may stream through this application at once. Each one
         * holds a thread and a buffer for as long as the transfer takes, and a replica count pinned
         * to 1 has no second process to absorb a burst - comfortably under Tomcat's 200-thread
         * default, with room left for ordinary request serving.
         */
        @DefaultValue("8") int maxConcurrentTransfers,

        /*
         * A per-board ceiling on how many attachments may exist at once, checked alongside {@link
         * #maxTotalBytesPerBoard} before a blob is written. 500 attachments is fifty of the largest
         * file this feature allows, which is generous for one board and still a number rather than
         * nothing.
         */
        @DefaultValue("500") long maxAttachmentsPerBoard,

        /*
         * A per-board ceiling on the combined size of every attachment, in bytes. One gibibyte -
         * a hundred files at the ten-megabyte per-file limit - is the same kind of small, explicit
         * number {@link pl.myproject.kanbanproject2.task.attachment.TaskAttachmentService
         * #MAX_ATTACHMENT_SIZE} is for one file.
         */
        @DefaultValue("1073741824") long maxTotalBytesPerBoard) {

    /** Whether there is enough here to store anything at all. */
    public boolean isConfigured() {
        return StringUtils.hasText(endpoint) || StringUtils.hasText(connectionString);
    }
}
