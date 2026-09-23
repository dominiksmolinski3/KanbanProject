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

@Slf4j
@Transactional
@Service
public class AvatarService {

    static final long MAX_AVATAR_SIZE = 1024 * 1024;

    private static final Set<String> ALLOWED_AVATAR_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    private final UserRepository userRepository;
    private final BlobStore blobStore;
    private final Clock clock;

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

    public void upload(User caller, MultipartFile file) {
        requireStorage();
        validate(file);
        String contentType = normalisedContentType(file);

        byte[] bytes = readFully(file);
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
        // Blob written, row not yet: if this transaction does not commit, nothing else knows the blob exists.
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

    private User findUser(Integer userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND,
                        "User not found with id: " + userId));
    }

    private static String blobNameFor(User user) {
        return "avatars/" + user.getId() + "/" + UUID.randomUUID();
    }

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

    private static String normalisedContentType(MultipartFile file) {
        var contentType = file.getContentType();
        if (contentType == null) {
            return "";
        }
        var separator = contentType.indexOf(';');
        var bare = separator < 0 ? contentType : contentType.substring(0, separator);
        return bare.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesDeclaredType(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/png" -> startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/jpeg" -> startsWith(bytes, 0xFF, 0xD8, 0xFF);
            case "image/gif" -> startsWith(bytes, 'G', 'I', 'F', '8');
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

    private void removeAfterCommit(String blobName) {
        onCompletion(status -> {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                removeQuietly(blobName);
            }
        });
    }

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

    private void removeQuietly(String blobName) {
        try {
            blobStore.remove(blobName);
        } catch (RuntimeException e) {
            log.warn("Left an orphaned avatar blob behind: {} ({})", blobName, e.getMessage());
        }
    }
}
