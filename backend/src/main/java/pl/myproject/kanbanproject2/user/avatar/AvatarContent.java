package pl.myproject.kanbanproject2.user.avatar;

import java.io.InputStream;

/**
 * One avatar's bytes, still in the store, plus what the response should call them. Shaped after
 * {@code TaskAttachmentContent}, minus the range fields - an avatar is small enough (capped at
 * {@link AvatarService#MAX_AVATAR_SIZE}) that resuming a partial download buys nothing a task
 * attachment's ten-megabyte ceiling does not already need. {@link #contentType} is read back from
 * the row rather than the blob, since the blob is opaque; {@link #sizeBytes} is what
 * {@code Content-Length} is set from, so a listing never costs a call to storage just to size the
 * response.
 */
public record AvatarContent(InputStream stream, String contentType, long sizeBytes) {
}
