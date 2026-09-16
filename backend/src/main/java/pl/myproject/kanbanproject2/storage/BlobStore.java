package pl.myproject.kanbanproject2.storage;

import java.io.InputStream;

/**
 * Where the bytes of an uploaded file live. The same seam as {@code EmailSender}: the feature above
 * names what it wants, and which provider carries it out is one bean. {@link DisabledBlobStore} runs
 * when no account is configured, so a fresh clone and CI start normally with an honest 503 on upload
 * rather than failing to boot.
 *
 * <p><b>Bytes go both ways through this application, deliberately.</b> The alternative - a signed
 * URL letting the browser fetch from Azure directly - would require the storage account to answer
 * arbitrary internet addresses, and an account holding every file on every board should not be
 * reachable from there. So the account is closed, reached only over a private endpoint, and a
 * download is streamed - never buffered - through here.
 */
public interface BlobStore {

    /**
     * Writes one blob. {@code length} is passed rather than discovered, since the caller always
     * knows it and it lets the client stream the body instead of buffering to find out how long it
     * is.
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
     * Opens part of one blob for reading. The caller closes it.
     *
     * <p>The range is asked of the <em>store</em> rather than served by skipping a full stream. A
     * {@code ResourceRegion} over {@link #read(String)} would answer the same 206 but still pull
     * every byte from zero to {@code offset} across the private endpoint first, so a resume at 90%
     * would cost the same egress as starting over.
     *
     * @param offset the first byte to read, counted from zero.
     * @param length how many bytes to read. The caller has already clamped this to the blob's own
     *               length, because the row that knows the size is the one deciding the range.
     * @throws BlobStoreException if the provider would not open it.
     */
    InputStream read(String blobName, long offset, long length);

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
