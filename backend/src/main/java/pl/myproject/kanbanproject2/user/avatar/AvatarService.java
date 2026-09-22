package pl.myproject.kanbanproject2.user.avatar;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.config.BlobStorageProperties;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.storage.BlobStore;
import pl.myproject.kanbanproject2.storage.BlobStoreException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Avatars in Azure Blob Storage, the same seam {@code TaskAttachmentService} uses - FEAT-09 moved
 * them out of Postgres for the same reason attachments moved out first: a {@code @Lob} put every
 * upload into the database's own storage, backups and restore window for data no query ever looked
 * inside. The write order is the attachment feature's own rule, for the same reason: upload writes
 * the blob first and undoes it if the transaction never commits, so the only failure available is
 * an orphaned blob rather than a row whose bytes are gone.
 *
 * <p><b>One deliberate difference from {@code TaskAttachmentService}: this reads the whole upload
 * into memory before it ever reaches the store.</b> An attachment streams straight through because
 * it can be ten megabytes and many can be in flight at once; an avatar is capped at
 * {@link #MAX_AVATAR_SIZE} - one megabyte - and the bytes are needed twice regardless, once to check
 * the declared {@code Content-Type} is not a lie (a browser trusts what {@code image/png} promises,
 * an attacker does not have to) and once to hand to the store. Reading a bounded megabyte to keep
 * that check is a better trade here than it would be at ten times the size and ten times the
 * concurrency.
 */
@Slf4j
@Transactional
@Service
public class AvatarService {

    static final long MAX_AVATAR_SIZE = 1024 * 1024;

    /*
     * An explicit list, not `image/*`: that prefix admits image/svg+xml, and an SVG is a document
     * with script in it. The stored type is echoed back on the app's own origin as
     * Content-Disposition: inline once this allow-list and the magic-byte check below both agree,
     * so a single upload past either one would be stored XSS against every viewer's token.
     */
    private static final Set<String> ALLOWED_AVATAR_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    private final UserRepository userRepository;
    private final BlobStore blobStore;
    private final Clock clock;

    /**
     * Sized the same way {@code TaskAttachmentService.transferPermits} is - dividing
     * {@code app.storage.max-concurrent-transfers} by {@code app.storage.replica-count-hint} - but
     * kept as its own pool rather than shared with attachments, so a burst of avatar uploads cannot
     * starve a task attachment transfer or the other way round.
     */
    private final Semaphore transferPermits;

    @Autowired
    public AvatarService(UserRepository userRepository, BlobStore blobStore,
                         BlobStorageProperties storageProperties) {
        this(userRepository, blobStore, storageProperties, Clock.systemUTC());
    }

    AvatarService(UserRepository userRepository, BlobStore blobStore,
                 BlobStorageProperties storageProperties, Clock clock) {
        this.userRepository = userRepository;
        this.blobStore = blobStore;
        this.clock = clock;
        this.transferPermits = new Semaphore(Math.max(1,
                storageProperties.maxConcurrentTransfers() / Math.max(1, storageProperties.replicaCountHint())));
    }

    /**
     * Validates, stores and records one avatar, replacing whatever the account had before. The old
     * blob (if there was one) is removed only after this transaction commits - deleting it first
     * would leave a user with no avatar at all if the write that follows never lands.
     */
    public void upload(User caller, MultipartFile file) {
        requireStorage();
        validate(file);
        String contentType = normalisedContentType(file);

        byte[] bytes = readFully(file);
        // The declared type is attacker-controlled, so it only narrows the set; the bytes decide.
        if (!matchesDeclaredType(contentType, bytes)) {
            throw new GlobalException(ExceptionIdentifier.INVALID_AVATAR_FILE_TYPE);
        }

        var user = findUser(caller.getId());
        String previousBlobName = user.getAvatarBlobName();
        String blobName = blobNameFor(user);

        if (!transferPermits.tryAcquire()) {
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_TRANSFER_BUSY);
        }
        try {
            blobStore.put(blobName, contentType, new ByteArrayInputStream(bytes), bytes.length);
        } catch (BlobStoreException e) {
            throw new GlobalException(ExceptionIdentifier.FILE_UPLOAD_FAILED, e);
        } finally {
            transferPermits.release();
        }
        // The blob exists and the row does not yet point at it. If this transaction never commits,
        // that blob is unreachable for good, so undo it on the way out rather than leaving it there.
        onCompletion(status -> {
            if (status != TransactionSynchronization.STATUS_COMMITTED) {
                removeQuietly(blobName);
            }
        });

        user.setAvatarBlobName(blobName);
        user.setAvatarContentType(contentType);
        user.setAvatarSizeBytes((long) bytes.length);
        user.setAvatarUploadedAt(clock.instant());
        userRepository.save(user);

        if (previousBlobName != null) {
            removeAfterCommit(previousBlobName);
        }
    }

    /**
     * Opens the stored avatar for reading. No caller parameter: who may look is answered by
     * {@code UserService.requireVisibleUser} before this is ever called - the same shape the
     * previous {@code AvatarService} had, and avatars have no visibility rule of their own beyond
     * the one {@code UserService} already enforces for the account itself. The transfer permit
     * acquired below is released from the stream's own {@code close()}, not here - Spring copies
     * the stream to the response afterward, so releasing on return would free the slot before the
     * transfer is done.
     */
    public AvatarContent content(Integer userId) {
        var user = findUser(userId);
        if (user.getAvatarBlobName() == null) {
            throw new GlobalException(ExceptionIdentifier.AVATAR_NOT_FOUND);
        }

        if (!transferPermits.tryAcquire()) {
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_TRANSFER_BUSY);
        }
        boolean handedOff = false;
        try {
            InputStream stream = blobStore.read(user.getAvatarBlobName());
            AvatarContent content = new AvatarContent(releasingOnClose(stream),
                    user.getAvatarContentType(), user.getAvatarSizeBytes());
            handedOff = true;
            return content;
        } catch (BlobStoreException e) {
            throw new GlobalException(ExceptionIdentifier.FILE_UPLOAD_FAILED, e);
        } finally {
            if (!handedOff) {
                transferPermits.release();
            }
        }
    }

    /** Releases one transfer permit the first time the stream is closed, and never again. */
    private InputStream releasingOnClose(InputStream in) {
        return new FilterInputStream(in) {
            private final AtomicBoolean released = new AtomicBoolean(false);

            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    if (released.compareAndSet(false, true)) {
                        transferPermits.release();
                    }
                }
            }
        };
    }

    public void delete(User caller) {
        var user = findUser(caller.getId());
        if (user.getAvatarBlobName() == null) {
            throw new GlobalException(ExceptionIdentifier.AVATAR_NOT_FOUND);
        }

        String blobName = user.getAvatarBlobName();
        user.setAvatarBlobName(null);
        user.setAvatarContentType(null);
        user.setAvatarSizeBytes(null);
        user.setAvatarUploadedAt(null);
        userRepository.save(user);

        removeAfterCommit(blobName);
    }

    // ------------------------------------------------------------------ lookups ---

    private User findUser(Integer userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND,
                        "User not found with id: " + userId));
    }

    // ------------------------------------------------------------------ blob names ---

    /** {@code avatars/<userId>/<uuid>} - opaque, the same shape {@code tasks/<taskId>/<uuid>} is. */
    private static String blobNameFor(User user) {
        return "avatars/" + user.getId() + "/" + UUID.randomUUID();
    }

    // ------------------------------------------------------------------ validation ---

    private void requireStorage() {
        if (!blobStore.isConfigured()) {
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE);
        }
    }

    private static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new GlobalException(ExceptionIdentifier.INVALID_AVATAR_FILE_TYPE,
                    "The uploaded file must not be empty");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new GlobalException(ExceptionIdentifier.AVATAR_FILE_TOO_LARGE);
        }
        if (!ALLOWED_AVATAR_TYPES.contains(normalisedContentType(file))) {
            throw new GlobalException(ExceptionIdentifier.INVALID_AVATAR_FILE_TYPE);
        }
    }

    private static byte[] readFully(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new GlobalException(ExceptionIdentifier.FILE_UPLOAD_FAILED, e);
        }
    }

    /** Browsers may append parameters, e.g. {@code image/jpeg; charset=binary}. */
    private static String normalisedContentType(MultipartFile file) {
        var contentType = file.getContentType();
        if (contentType == null) {
            return "";
        }
        var separator = contentType.indexOf(';');
        var bare = separator < 0 ? contentType : contentType.substring(0, separator);
        return bare.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The bytes decide, not the header. Each signature is the first bytes of the real format, so a
     * file typed {@code image/png} that is not actually one - an SVG with a lying header, or
     * anything else - is refused here even though it passed the allow-list above.
     */
    private static boolean matchesDeclaredType(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/png" -> startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/jpeg" -> startsWith(bytes, 0xFF, 0xD8, 0xFF);
            case "image/gif" -> startsWith(bytes, 'G', 'I', 'F', '8');
            // RIFF....WEBP - the four size bytes at offset 4 are skipped.
            case "image/webp" -> startsWith(bytes, 'R', 'I', 'F', 'F')
                    && bytes.length >= 12
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }

    private static boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ the two-system dance ---

    private void removeAfterCommit(String blobName) {
        onCompletion(status -> {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                removeQuietly(blobName);
            }
        });
    }

    /**
     * Runs {@code action} when the surrounding transaction finishes, or immediately when there is
     * none - treating "nothing to roll back" as committed, which keeps this correct even if a
     * caller ever runs outside a transaction.
     */
    private static void onCompletion(IntConsumer action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.accept(TransactionSynchronization.STATUS_COMMITTED);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                action.accept(status);
            }
        });
    }

    /**
     * A blob removal that cannot fail the request. Everything that calls this has already
     * committed or already given up, so throwing would turn a working delete into a 500 without
     * undoing anything.
     */
    private void removeQuietly(String blobName) {
        try {
            blobStore.remove(blobName);
        } catch (RuntimeException e) {
            log.warn("Left an orphaned avatar blob behind: {} ({})", blobName, e.getMessage());
        }
    }
}
