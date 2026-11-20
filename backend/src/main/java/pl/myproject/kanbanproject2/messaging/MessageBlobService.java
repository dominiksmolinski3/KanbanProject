package pl.myproject.kanbanproject2.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.config.BlobStorageProperties;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.storage.BlobStore;
import pl.myproject.kanbanproject2.storage.BlobStoreException;
import pl.myproject.kanbanproject2.user.User;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
public class MessageBlobService {

    static final String BLOB_PREFIX = "messages/";

    public static final long MAX_BLOB_BYTES = 10L * 1024 * 1024;

    private final ConversationAccess access;
    private final MessageBlobRepository blobs;
    private final BlobStore blobStore;
    private final BoardService boards;
    private final TransactionOperations transactions;
    private final long maxBytesPerConversation;
    private final Clock clock;

    @Autowired
    public MessageBlobService(ConversationAccess access, MessageBlobRepository blobs, BlobStore blobStore,
                              BoardService boards, TransactionOperations transactions,
                              BlobStorageProperties properties) {
        this(access, blobs, blobStore, boards, transactions, properties, Clock.systemUTC());
    }

    MessageBlobService(ConversationAccess access, MessageBlobRepository blobs, BlobStore blobStore,
                       BoardService boards, TransactionOperations transactions, BlobStorageProperties properties,
                       Clock clock) {
        this.access = access;
        this.blobs = blobs;
        this.blobStore = blobStore;
        this.boards = boards;
        this.transactions = transactions;
        this.maxBytesPerConversation = properties.maxTotalBytesPerBoard();
        this.clock = clock;
    }

    public MessageBlobDto upload(User caller, Long conversationId, MultipartFile file) {
        String blobName = transactions.execute(status -> {
            ConversationMember me = access.requireMember(caller, conversationId);
            if (me.getConversation().getBoardId() != null) {
                boards.requireWritable(caller, me.getConversation().getBoardId());
            }
            if (!blobStore.isConfigured()) {
                throw new GlobalException(ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE);
            }
            validate(file);
            if (blobs.totalSize(conversationId) + file.getSize() > maxBytesPerConversation) {
                throw new GlobalException(ExceptionIdentifier.ATTACHMENT_QUOTA_EXCEEDED,
                        "Ta rozmowa wykorzystała limit miejsca na załączniki");
            }
            return BLOB_PREFIX + conversationId + "/" + UUID.randomUUID();
        });
        try (InputStream data = file.getInputStream()) {
            blobStore.put(blobName, "application/octet-stream", data, file.getSize());
        } catch (IOException | BlobStoreException e) {
            throw new GlobalException(ExceptionIdentifier.FILE_UPLOAD_FAILED, e);
        }
        try {
            return transactions.execute(status -> {
                ConversationMember me = access.requireMember(caller, conversationId);
                Instant now = clock.instant();
                MessageBlob saved = blobs.save(new MessageBlob(me.getConversation(), caller, blobName, file.getSize(),
                        now, MessageDeliveryService.expiry(me.getConversation(), now)));
                return new MessageBlobDto(saved.getId(), saved.getSizeBytes());
            });
        } catch (RuntimeException e) {
            removeQuietly(blobName);
            throw e;
        }
    }

    public MessageBlobContent content(User caller, Long conversationId, Long blobId) {
        MessageBlob blob = transactions.execute(status -> {
            access.requireMember(caller, conversationId);
            return blobs.findInConversation(blobId, conversationId).orElseThrow(() ->
                    new GlobalException(ExceptionIdentifier.ATTACHMENT_NOT_FOUND, "Nie znaleziono załącznika"));
        });
        try {
            return new MessageBlobContent(blobStore.read(blob.getBlobName()), blob.getSizeBytes());
        } catch (BlobStoreException e) {
            throw new GlobalException(ExceptionIdentifier.FILE_UPLOAD_FAILED, e);
        }
    }

    void removeQuietly(String blobName) {
        try {
            blobStore.remove(blobName);
        } catch (BlobStoreException e) {
            log.warn("Could not remove message blob {}: {}", blobName, e.getMessage());
        }
    }

    private static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new GlobalException(ExceptionIdentifier.INVALID_ATTACHMENT, "Plik nie może być pusty");
        }
        if (file.getSize() > MAX_BLOB_BYTES) {
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_TOO_LARGE);
        }
    }
}
