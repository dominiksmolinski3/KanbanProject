package pl.myproject.kanbanproject2.storage;

import java.io.InputStream;

/**
 * Where the bytes of an uploaded file live.
 *
 * <p>The same seam {@code EmailSender} is, for the same reason: the feature above this line names
 * what it wants, and which provider carries it out is one bean. {@link DisabledBlobStore} is what
 * runs when no account is configured, so a fresh clone and CI start normally and refuse uploads
 * with an honest 503 rather than failing to boot.
 *
 * <p><b>Bytes go both ways through this application, and that is a decision.</b> The alternative
 * was to hand the browser a signed URL and let it fetch from Azure directly, which keeps ten
 * megabytes off a container sized at a quarter of a CPU. It also requires the storage account to
 * answer arbitrary addresses on the internet, because that is where browsers are - and a storage
 * account holding every file on every board should not be reachable from there at all. So the
 * account is closed, the application reaches it over a private endpoint, and a download is streamed
 * through here. Streamed, not buffered: nothing on this path ever holds a whole file.
 */
public interface BlobStore {

    /**
     * Writes one blob.
     *
     * <p>{@code length} is passed rather than discovered because the caller always knows it - a
     * multipart part carries its size - and knowing it is what lets the client stream the body
     * instead of buffering to find out how long it is.
     *
     * @throws BlobStoreException if the provider would not take it.
     */
    void put(String blobName, String contentType, InputStream data, long length);

    /**
     * Opens one blob for reading. The caller closes it.
     *
     * <p>An open stream rather than a byte array, so a ten-megabyte download costs a buffer rather
     * than ten megabytes of heap per concurrent reader.
     *
     * @throws BlobStoreException if the provider would not open it.
     */
    InputStream read(String blobName);

    /**
     * Removes one blob, and says nothing if it was already gone.
     *
     * <p>Idempotent on purpose: this is called after the row is committed, so the interesting case
     * is a retry, not an absence.
     */
    void remove(String blobName);

    /**
     * Whether this store actually holds anything.
     *
     * <p>Asked in two places only: the upload path, which refuses early rather than after reading
     * ten megabytes off the wire, and the health indicator, which is how a deployment that forgot
     * to configure storage finds out before its users do.
     */
    default boolean isConfigured() {
        return true;
    }
}
