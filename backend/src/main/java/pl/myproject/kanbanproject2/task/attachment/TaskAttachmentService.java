package pl.myproject.kanbanproject2.task.attachment;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpRange;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.config.BlobStorageProperties;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.storage.BlobStore;
import pl.myproject.kanbanproject2.storage.BlobStoreException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.user.User;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Files attached to a task, held in Azure Blob Storage with a row here to name them. Every lookup
 * goes through {@link #findTask}, the same rule {@code TaskService} states, since an attachment has
 * no visibility of its own — the task decides who may see it. A blob and a row can't be written
 * atomically, so the write order is chosen for the safer failure: upload writes the blob first and
 * removes it if the transaction doesn't commit, while delete removes the row first and the blob
 * after commit — either way risks an orphaned, invisible blob rather than a row pointing at bytes
 * that are gone, which would fail every time somebody clicks it. {@link #transferPermits} bounds
 * concurrent transfers so a burst gets a fast {@code 503} instead of a hung connection, divided
 * across the fleet rather than sized per container, and {@link #requireQuota} bounds what a board
 * may accumulate, checked
 * before the blob is written so a rejected upload never leaks one. {@link #content} resolves a
 * {@code Range} against the stored size before touching storage, so a transfer that died at 90%
 * resumes for the last tenth instead of paying for the whole file again.
 */
@Slf4j
@Transactional
@Service
public class TaskAttachmentService {

    /**
     * Ten megabytes, matching {@code spring.servlet.multipart.max-file-size}. Checked here too,
     * since the container's limit is a transport setting that answers with a different error shape.
     */
    static final long MAX_ATTACHMENT_SIZE = 10L * 1024 * 1024;

    /** Long enough for a real file name, short enough to fit the column that stores it. */
    private static final int MAX_FILE_NAME_LENGTH = 255;

    /**
     * What an upload with no declared type is stored as. A guess would be worse: every link forces
     * {@code Content-Disposition: attachment}, so the type only decides what the browser offers to
     * open the file with, never whether it renders.
     */
    private static final String UNKNOWN_CONTENT_TYPE = "application/octet-stream";

    private final TaskAttachmentRepository attachments;
    private final TaskRepository tasks;
    private final TaskAttachmentMapper mapper;
    private final BlobStore blobStore;
    private final Clock clock;

    /**
     * Bounds concurrent uploads and downloads; sized by dividing
     * {@code app.storage.max-concurrent-transfers} across {@code app.storage.replica-count-hint}
     * replicas, each JVM enforcing its own equal share. That keeps the configured number meaning
     * "total across the fleet" rather than "per replica" once api_max_replicas rises above 1, at
     * the cost of undercounting capacity while the deployment is scaled below its ceiling - the
     * safe direction to be wrong in.
     */
    private final Semaphore transferPermits;
    private final long maxAttachmentsPerBoard;
    private final long maxTotalBytesPerBoard;

    @Autowired
    public TaskAttachmentService(TaskAttachmentRepository attachments,
                                 TaskRepository tasks,
                                 TaskAttachmentMapper mapper,
                                 BlobStore blobStore,
                                 BlobStorageProperties storageProperties) {
        this(attachments, tasks, mapper, blobStore, storageProperties, Clock.systemUTC());
    }

    TaskAttachmentService(TaskAttachmentRepository attachments,
                          TaskRepository tasks,
                          TaskAttachmentMapper mapper,
                          BlobStore blobStore,
                          BlobStorageProperties storageProperties,
                          Clock clock) {
        this.attachments = attachments;
        this.tasks = tasks;
        this.mapper = mapper;
        this.blobStore = blobStore;
        this.clock = clock;
        this.transferPermits = new Semaphore(Math.max(1,
                storageProperties.maxConcurrentTransfers() / Math.max(1, storageProperties.replicaCountHint())));
        this.maxAttachmentsPerBoard = storageProperties.maxAttachmentsPerBoard();
        this.maxTotalBytesPerBoard = storageProperties.maxTotalBytesPerBoard();
    }

    public List<TaskAttachmentDto> list(User caller, Integer taskId) {
        return attachments.findByTaskOrderByUploadedAtAscIdAsc(findTask(caller, taskId)).stream()
                .map(mapper)
                .toList();
    }

    /**
     * Streams one upload into the container and records it. The stream is handed to the store
     * rather than read into an array, since a ten-megabyte {@code getBytes()} per concurrent upload
     * is a real fraction of a container sized at 512 MB.
     */
    public TaskAttachmentDto upload(User caller, Integer taskId, MultipartFile file) {
        var task = findTask(caller, taskId);
        requireStorage();
        validate(file);
        requireQuota(task.getBoard(), file.getSize());

        String fileName = StringUtils.cleanPath(file.getOriginalFilename());
        String contentType = contentTypeOf(file);
        String blobName = blobNameFor(task);

        if (!transferPermits.tryAcquire()) {
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_TRANSFER_BUSY);
        }
        try (InputStream data = file.getInputStream()) {
            blobStore.put(blobName, contentType, data, file.getSize());
        } catch (IOException | BlobStoreException e) {
            throw new GlobalException(ExceptionIdentifier.FILE_UPLOAD_FAILED, e);
        } finally {
            transferPermits.release();
        }
        // The blob exists and the row does not yet. If this transaction never commits, that blob
        // is unreachable for good, so undo it on the way out rather than leaving it there.
        onCompletion(status -> {
            if (status != TransactionSynchronization.STATUS_COMMITTED) {
                removeQuietly(blobName);
            }
        });

        var attachment = new TaskAttachment();
        attachment.setTask(task);
        attachment.setBlobName(blobName);
        attachment.setFileName(fileName);
        attachment.setContentType(contentType);
        attachment.setSizeBytes(file.getSize());
        attachment.setUploadedBy(caller);
        attachment.setUploadedAt(clock.instant());

        return mapper.apply(attachments.save(attachment));
    }

    /**
     * Opens one attachment for reading, having first decided the caller may. This is the whole
     * reason the storage account can be closed to the internet: the only thing that ever talks to
     * it is this application, over a private endpoint, rather than a signed URL handed to the
     * browser. The stream is returned open rather than read here to check it, since holding the
     * file is the one thing this path must not do. The transfer permit acquired below is
     * <b>not</b> released when this method returns — Spring copies the stream to the response
     * afterward, so the permit is released from the stream's own {@code close()} instead, except on
     * the failure path, where no stream was ever handed out. {@code range} is resolved here rather
     * than in the controller because a suffix range needs the size to become an offset, and the
     * permit is taken only after that check, so a request that was never going to be served costs
     * no slot.
     *
     * @param range the single range asked for, or {@code null} for the whole file.
     * @throws UnsatisfiableRangeException if the range names bytes the attachment does not have.
     */
    public TaskAttachmentContent content(User caller, Integer taskId, Long attachmentId, HttpRange range) {
        var attachment = findAttachment(caller, taskId, attachmentId);
        long size = attachment.getSizeBytes();
        long start = 0;
        long length = size;
        if (range != null) {
            start = range.getRangeStart(size);
            long end = range.getRangeEnd(size);
            // Both forms land here: `bytes=900-` past the end gives start >= size, and `bytes=-0`
            // gives an end below its own start. Either way the caller is asking for bytes that do
            // not exist, and the honest answer names the length rather than quietly serving less.
            if (start >= size || end < start) {
                throw new UnsatisfiableRangeException(size);
            }
            length = end - start + 1;
        }

        if (!transferPermits.tryAcquire()) {
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_TRANSFER_BUSY);
        }
        boolean handedOff = false;
        try {
            // The unranged read stays the unranged call rather than a range covering everything,
            // so an ordinary download reaches storage exactly as it did before this existed.
            InputStream stream = range == null
                    ? blobStore.read(attachment.getBlobName())
                    : blobStore.read(attachment.getBlobName(), start, length);
            TaskAttachmentContent content = range == null
                    ? TaskAttachmentContent.whole(releasingOnClose(stream), attachment.getFileName(),
                            attachment.getContentType(), size)
                    : TaskAttachmentContent.part(releasingOnClose(stream), attachment.getFileName(),
                            attachment.getContentType(), size, start, length);
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

    public void delete(User caller, Integer taskId, Long attachmentId) {
        var attachment = findAttachment(caller, taskId, attachmentId);
        attachments.delete(attachment);
        removeAfterCommit(attachment.getBlobName());
    }

    /**
     * Everything hanging off a task that is being deleted. Called by
     * {@code TaskService.deleteTask} rather than a cascade, since an {@code ON DELETE CASCADE}
     * would take the rows and leave every blob behind with nothing left that knows the names. No
     * caller parameter: already checked by the task lookup that found the task being deleted.
     */
    public void deleteAllFor(Task task) {
        var toDelete = attachments.findByTask(task);
        if (toDelete.isEmpty()) {
            return;
        }
        attachments.deleteAll(toDelete);
        toDelete.forEach(attachment -> removeAfterCommit(attachment.getBlobName()));
    }

    // ------------------------------------------------------------------ lookups ---

    /** The task, or a 404 that does not say whether it exists on somebody else's board. */
    private Task findTask(User caller, Integer taskId) {
        var task = tasks.findById(taskId).orElseThrow(() -> taskNotFound(taskId));
        if (!task.getBoard().isVisibleTo(caller)) {
            throw taskNotFound(taskId);
        }
        return task;
    }

    /**
     * An attachment that is on the named task, and on a task the caller can see. The task is
     * checked first and the attachment matched against it, rather than looked up and its task read
     * back — otherwise an id from another board would be reachable through any task the caller
     * does own, and the path would be decoration.
     */
    private TaskAttachment findAttachment(User caller, Integer taskId, Long attachmentId) {
        var task = findTask(caller, taskId);
        var attachment = attachments.findById(attachmentId)
                .orElseThrow(() -> attachmentNotFound(attachmentId));
        if (attachment.getTask() == null || !task.getId().equals(attachment.getTask().getId())) {
            throw attachmentNotFound(attachmentId);
        }
        return attachment;
    }

    // ------------------------------------------------------------------ blob names ---

    /**
     * {@code tasks/<taskId>/<uuid>} - a prefix that makes the container browsable by task, and a
     * name with nothing in it that anybody typed.
     */
    private static String blobNameFor(Task task) {
        return "tasks/" + task.getId() + "/" + UUID.randomUUID();
    }

    // ------------------------------------------------------------------ validation ---

    private void requireStorage() {
        if (!blobStore.isConfigured()) {
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE);
        }
    }

    /**
     * A board-wide ceiling on attachments — count and total bytes, both enforced — checked before
     * the blob is written so a rejected upload never leaves an orphaned blob behind.
     */
    private void requireQuota(Board board, long incomingBytes) {
        if (attachments.countByTaskBoard(board) + 1 > maxAttachmentsPerBoard) {
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_QUOTA_EXCEEDED,
                    "This board already has the maximum number of attachments allowed");
        }
        if (attachments.totalSizeBytesByTaskBoard(board) + incomingBytes > maxTotalBytesPerBoard) {
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_QUOTA_EXCEEDED,
                    "This board has reached its total attachment storage quota");
        }
    }

    private static void validate(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw invalid("The uploaded file must not be empty");
        }
        if (file.getSize() > MAX_ATTACHMENT_SIZE) {
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_TOO_LARGE);
        }
        if (!StringUtils.hasText(file.getOriginalFilename())) {
            throw invalid("The uploaded file must have a name");
        }
        String cleaned = StringUtils.cleanPath(file.getOriginalFilename());
        // cleanPath resolves the traversal it can and leaves what it cannot, so both are checked.
        if (!StringUtils.hasText(cleaned) || cleaned.contains("..") || cleaned.contains("/")) {
            throw invalid("The uploaded file name is invalid");
        }
        if (cleaned.length() > MAX_FILE_NAME_LENGTH) {
            throw invalid("The uploaded file name is too long");
        }
    }

    /** Browsers append parameters - {@code text/plain; charset=utf-8} - and the column stores one type. */
    private static String contentTypeOf(MultipartFile file) {
        String declared = file.getContentType();
        if (!StringUtils.hasText(declared)) {
            return UNKNOWN_CONTENT_TYPE;
        }
        int separator = declared.indexOf(';');
        String bare = (separator < 0 ? declared : declared.substring(0, separator)).trim();
        return bare.isEmpty() ? UNKNOWN_CONTENT_TYPE : bare.toLowerCase(Locale.ROOT);
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
     * none — treating "nothing to roll back" as committed, which keeps this correct even if a
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
            log.warn("Left an orphaned blob behind: {} ({})", blobName, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ errors ---

    private static GlobalException invalid(String message) {
        return new GlobalException(ExceptionIdentifier.INVALID_ATTACHMENT, message);
    }

    private static GlobalException taskNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.TASK_NOT_FOUND, "Task not found with id: " + id);
    }

    private static GlobalException attachmentNotFound(Long id) {
        return new GlobalException(ExceptionIdentifier.ATTACHMENT_NOT_FOUND,
                "Attachment not found with id: " + id);
    }
}
