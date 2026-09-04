package pl.myproject.kanbanproject2.task.attachment;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.storage.BlobStore;
import pl.myproject.kanbanproject2.storage.BlobStoreException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.user.User;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Files attached to a task, held in Azure Blob Storage with a row here to name them.
 *
 * <p>Every method takes the caller and every lookup goes through {@link #findTask}, the same rule
 * {@code TaskService} states: an attachment on a board the caller is not a member of answers as one
 * that does not exist. The scoping question is answered entirely by the task - an attachment has no
 * visibility of its own, which is why the routes name the task in the path and this service checks
 * the task before it looks at anything else.
 *
 * <p><b>Two systems, one order, and the failure that order chooses.</b> A blob and a row have to
 * agree, and nothing makes them agree atomically. On upload the blob is written first and the row
 * second, with the blob removed again if the transaction does not commit. On delete the row goes
 * first and the blob after the commit. Both orders leave the same failure available - a blob with
 * no row, if the process dies in the window - and rule out the other one, a row whose bytes are
 * gone. That is deliberate: an orphaned blob costs a fraction of a cent and is invisible, while a
 * row pointing at nothing is an attachment in the list that fails every time somebody clicks it.
 */
@Slf4j
@Transactional
@Service
public class TaskAttachmentService {

    /**
     * Ten megabytes, matching {@code spring.servlet.multipart.max-file-size} and the limit the
     * existing upload route carries. Checked here as well as by the container, because the
     * container's limit is a transport setting that answers with a different shape of error.
     */
    static final long MAX_ATTACHMENT_SIZE = 10L * 1024 * 1024;

    /** Long enough for a real file name, short enough to fit the column that stores it. */
    private static final int MAX_FILE_NAME_LENGTH = 255;

    /**
     * What an upload with no declared type is stored as.
     *
     * <p>A guess would be worse. Every link this service hands out forces {@code Content-Disposition:
     * attachment}, so the type does not decide whether something is rendered - it only decides what
     * the browser offers to open the saved file with, and being honest about not knowing is the
     * better answer there.
     */
    private static final String UNKNOWN_CONTENT_TYPE = "application/octet-stream";

    private final TaskAttachmentRepository attachments;
    private final TaskRepository tasks;
    private final TaskAttachmentMapper mapper;
    private final BlobStore blobStore;
    private final Clock clock;

    @Autowired
    public TaskAttachmentService(TaskAttachmentRepository attachments,
                                 TaskRepository tasks,
                                 TaskAttachmentMapper mapper,
                                 BlobStore blobStore) {
        this(attachments, tasks, mapper, blobStore, Clock.systemUTC());
    }

    TaskAttachmentService(TaskAttachmentRepository attachments,
                          TaskRepository tasks,
                          TaskAttachmentMapper mapper,
                          BlobStore blobStore,
                          Clock clock) {
        this.attachments = attachments;
        this.tasks = tasks;
        this.mapper = mapper;
        this.blobStore = blobStore;
        this.clock = clock;
    }

    public List<TaskAttachmentDto> list(User caller, Integer taskId) {
        return attachments.findByTaskOrderByUploadedAtAscIdAsc(findTask(caller, taskId)).stream()
                .map(mapper)
                .toList();
    }

    /**
     * Streams one upload into the container and records it.
     *
     * <p>The stream is handed to the store rather than read into an array: a ten-megabyte
     * {@code getBytes()} per concurrent upload is a real fraction of a container sized at 512 MB,
     * and there is nothing in this path that needs to look at the bytes.
     */
    public TaskAttachmentDto upload(User caller, Integer taskId, MultipartFile file) {
        var task = findTask(caller, taskId);
        requireStorage();
        validate(file);

        String fileName = StringUtils.cleanPath(file.getOriginalFilename());
        String contentType = contentTypeOf(file);
        String blobName = blobNameFor(task);

        try (InputStream data = file.getInputStream()) {
            blobStore.put(blobName, contentType, data, file.getSize());
        } catch (IOException | BlobStoreException e) {
            throw new GlobalException(ExceptionIdentifier.FILE_UPLOAD_FAILED, e);
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
     * Opens one attachment for reading, having first decided the caller may.
     *
     * <p>This is the whole reason the storage account can be closed to the internet. A signed URL
     * handed to the browser would be faster and cheaper, and would require the account to answer
     * every address a browser might arrive from; going through here means the only thing that ever
     * talks to storage is this application, over a private endpoint, as itself.
     *
     * <p>The stream is returned open. Reading it here to check it would mean holding the file,
     * which is the one thing this path must not do.
     */
    public TaskAttachmentContent content(User caller, Integer taskId, Long attachmentId) {
        var attachment = findAttachment(caller, taskId, attachmentId);
        try {
            return new TaskAttachmentContent(
                    blobStore.read(attachment.getBlobName()),
                    attachment.getFileName(),
                    attachment.getContentType(),
                    attachment.getSizeBytes());
        } catch (BlobStoreException e) {
            throw new GlobalException(ExceptionIdentifier.FILE_UPLOAD_FAILED, e);
        }
    }

    public void delete(User caller, Integer taskId, Long attachmentId) {
        var attachment = findAttachment(caller, taskId, attachmentId);
        attachments.delete(attachment);
        removeAfterCommit(attachment.getBlobName());
    }

    /**
     * Everything hanging off a task that is being deleted.
     *
     * <p>Called by {@code TaskService.deleteTask} rather than done by a cascade, because the blobs
     * are not the database's to remove: a {@code ON DELETE CASCADE} would take the rows and leave
     * every one of their blobs behind with nothing left that knows the names.
     *
     * <p>No caller parameter - the caller has already been checked by the task lookup that found
     * the task being deleted, and this is not reachable from a route.
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
     * An attachment that is on the named task, and on a task the caller can see.
     *
     * <p>The task is checked first and the attachment matched against it, rather than the
     * attachment being looked up and its task read back. Otherwise an id from another board would
     * be reachable by naming any task the caller does have - the path would be decoration.
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
     * no transaction to wait for.
     *
     * <p>The immediate branch is not only for tests: it keeps this correct if a caller ever runs
     * outside one, by treating "nothing to roll back" as the committed case, which is what it is.
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
     * A blob removal that cannot fail the request.
     *
     * <p>Everything that calls this has already committed, or has already given up. Throwing would
     * turn a delete that worked into a 500, and would not put the blob back.
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
