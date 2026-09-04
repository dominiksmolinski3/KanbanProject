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
        String identityClientId) {

    /** Whether there is enough here to store anything at all. */
    public boolean isConfigured() {
        return StringUtils.hasText(endpoint) || StringUtils.hasText(connectionString);
    }
}
