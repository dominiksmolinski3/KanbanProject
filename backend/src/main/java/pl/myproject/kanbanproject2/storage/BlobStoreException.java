package pl.myproject.kanbanproject2.storage;

/**
 * The provider would not do what it was asked. Thrown by {@link BlobStore} implementations and
 * translated at the service boundary, so nothing above the storage package handles an Azure type.
 */
public class BlobStoreException extends RuntimeException {

    public BlobStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
