package pl.myproject.kanbanproject2.task.attachment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpRange;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.BoardTasksDeleting;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
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

@Slf4j
@Transactional
@Service
public class TaskAttachmentService {

    static final long MAX_ATTACHMENT_SIZE = 10L * 1024 * 1024;

    private static final int MAX_FILE_NAME_LENGTH = 255;

    private static final String UNKNOWN_CONTENT_TYPE = "application/octet-stream";

    private final TaskAttachmentRepository attachments;
    private final TaskRepository tasks;
    private final TaskAttachmentMapper mapper;
    private final BlobStore blobStore;
    private final BoardService boardService;
    private final BoardEventPublisher boardEvents;
    private final Clock clock;

    private final Semaphore transferPermits;
    private final long maxAttachmentsPerBoard;
    private final long maxTotalBytesPerBoard;

    static final String TRANSFER_BUSY_COUNTER = "kanban.attachment.transfer.refused";

    private final Counter uploadBusy;
    private final Counter downloadBusy;

    @Autowired
    public TaskAttachmentService(TaskAttachmentRepository attachments,
                                 TaskRepository tasks,
                                 TaskAttachmentMapper mapper,
                                 BlobStore blobStore,
                                 BoardService boardService,
                                 BoardEventPublisher boardEvents,
                                 BlobStorageProperties storageProperties,
                                 MeterRegistry meterRegistry) {
        this(attachments, tasks, mapper, blobStore, boardService, boardEvents, storageProperties,
                Clock.systemUTC(), meterRegistry);
    }

    TaskAttachmentService(TaskAttachmentRepository attachments,
                          TaskRepository tasks,
                          TaskAttachmentMapper mapper,
                          BlobStore blobStore,
                          BoardService boardService,
                          BoardEventPublisher boardEvents,
                          BlobStorageProperties storageProperties,
                          Clock clock,
                          MeterRegistry meterRegistry) {
        this.attachments = attachments;
        this.tasks = tasks;
        this.mapper = mapper;
        this.blobStore = blobStore;
        this.boardService = boardService;
        this.boardEvents = boardEvents;
        this.clock = clock;
        this.transferPermits = new Semaphore(Math.max(1,
                storageProperties.maxConcurrentTransfers() / Math.max(1, storageProperties.replicaCountHint())));
        this.maxAttachmentsPerBoard = storageProperties.maxAttachmentsPerBoard();
        this.maxTotalBytesPerBoard = storageProperties.maxTotalBytesPerBoard();
        this.uploadBusy = meterRegistry.counter(TRANSFER_BUSY_COUNTER, "operation", "upload");
        this.downloadBusy = meterRegistry.counter(TRANSFER_BUSY_COUNTER, "operation", "download");
    }

    public List<TaskAttachmentDto> list(User caller, Integer taskId) {
        return attachments.findByTaskOrderByUploadedAtAscIdAsc(findTask(caller, taskId)).stream()
                .map(mapper)
                .toList();
    }

    public TaskAttachmentDto upload(User caller, Integer taskId, MultipartFile file) {
        var task = findTask(caller, taskId);
        boardService.requireWritable(caller, task.getBoard());
        requireStorage();
        validate(file);
        requireQuota(task.getBoard(), file.getSize());

        String fileName = StringUtils.cleanPath(file.getOriginalFilename());
        String contentType = contentTypeOf(file);
        String blobName = blobNameFor(task);

        if (!transferPermits.tryAcquire()) {
            uploadBusy.increment();
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_TRANSFER_BUSY);
        }
        try (InputStream data = file.getInputStream()) {
            blobStore.put(blobName, contentType, data, file.getSize());
        } catch (IOException | BlobStoreException e) {
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

        var attachment = new TaskAttachment();
        attachment.setTask(task);
        attachment.setBlobName(blobName);
        attachment.setFileName(fileName);
        attachment.setContentType(contentType);
        attachment.setSizeBytes(file.getSize());
        attachment.setUploadedBy(caller);
        attachment.setUploadedAt(clock.instant());

        var saved = attachments.save(attachment);
        boardEvents.attachmentsChanged(task.getBoard());
        return mapper.apply(saved);
    }

    public TaskAttachmentContent content(User caller, Integer taskId, Long attachmentId, HttpRange range) {
        var attachment = findAttachment(caller, taskId, attachmentId);
        long size = attachment.getSizeBytes();
        long start = 0;
        long length = size;
        if (range != null) {
            start = range.getRangeStart(size);
            long end = range.getRangeEnd(size);
            if (start >= size || end < start) {
                throw new UnsatisfiableRangeException(size);
            }
            length = end - start + 1;
        }

        if (!transferPermits.tryAcquire()) {
            downloadBusy.increment();
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_TRANSFER_BUSY);
        }
        boolean handedOff = false;
        try {
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
        boardService.requireWritable(caller, attachment.getTask().getBoard());
        attachments.delete(attachment);
        removeAfterCommit(attachment.getBlobName());
        boardEvents.attachmentsChanged(attachment.getTask().getBoard());
    }

    public void deleteAllFor(Task task) {
        removeAll(attachments.findByTask(task));
    }

    @EventListener
    public void onBoardTasksDeleting(BoardTasksDeleting event) {
        if (event.tasks() == null || event.tasks().isEmpty()) {
            return;
        }
        removeAll(attachments.findByTaskIn(event.tasks()));
    }

    private void removeAll(List<TaskAttachment> toDelete) {
        if (toDelete.isEmpty()) {
            return;
        }
        attachments.deleteAll(toDelete);
        toDelete.forEach(attachment -> removeAfterCommit(attachment.getBlobName()));
    }

    private Task findTask(User caller, Integer taskId) {
        var task = tasks.findById(taskId).orElseThrow(() -> taskNotFound(taskId));
        if (!task.getBoard().isVisibleTo(caller)) {
            throw taskNotFound(taskId);
        }
        return task;
    }

    private TaskAttachment findAttachment(User caller, Integer taskId, Long attachmentId) {
        var task = findTask(caller, taskId);
        var attachment = attachments.findById(attachmentId)
                .orElseThrow(() -> attachmentNotFound(attachmentId));
        if (attachment.getTask() == null || !task.getId().equals(attachment.getTask().getId())) {
            throw attachmentNotFound(attachmentId);
        }
        return attachment;
    }

    private static String blobNameFor(Task task) {
        return "tasks/" + task.getId() + "/" + UUID.randomUUID();
    }

    private void requireStorage() {
        if (!blobStore.isConfigured()) {
            throw new GlobalException(ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE);
        }
    }

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
        if (!StringUtils.hasText(cleaned) || cleaned.contains("..") || cleaned.contains("/")) {
            throw invalid("The uploaded file name is invalid");
        }
        if (cleaned.length() > MAX_FILE_NAME_LENGTH) {
            throw invalid("The uploaded file name is too long");
        }
    }

    private static String contentTypeOf(MultipartFile file) {
        String declared = file.getContentType();
        if (!StringUtils.hasText(declared)) {
            return UNKNOWN_CONTENT_TYPE;
        }
        int separator = declared.indexOf(';');
        String bare = (separator < 0 ? declared : declared.substring(0, separator)).trim();
        return bare.isEmpty() ? UNKNOWN_CONTENT_TYPE : bare.toLowerCase(Locale.ROOT);
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
            log.warn("Left an orphaned blob behind: {} ({})", blobName, e.getMessage());
        }
    }

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
