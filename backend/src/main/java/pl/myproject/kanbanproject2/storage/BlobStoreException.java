package pl.myproject.kanbanproject2.storage;

public class BlobStoreException extends RuntimeException {
    public BlobStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
