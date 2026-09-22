package pl.myproject.kanbanproject2.task.comment;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.BoardTasksDeleting;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.activity.TaskActivityRecorder;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * A card's comment thread (FEAT-06), built from parts this repository already had: scoped through
 * the task the way {@code TaskAttachmentService} is, paged with the activity feed's numbers and its
 * refusal, announced on the board topic the way every other board change is, and recorded in the
 * activity feed so {@code /activity} says who said something where.
 *
 * <p>Three rules decide who may do what, and each is checked on the way in rather than trusted from
 * the client's own copy of them:
 * <ul>
 *   <li><b>Reading follows the card.</b> Anybody who can see the task can read its thread, a viewer
 *       included - looking at a card and not at what was said about it would be half a look.</li>
 *   <li><b>Writing follows the board.</b> A viewer may not comment. FEAT-08 answered "does looking
 *       include talking" for chat already, and a comment is the same act at a smaller scale.</li>
 *   <li><b>A comment is its author's.</b> Only the author may rewrite it; the author or the board's
 *       owner may remove it, since an owner who cannot take down something posted on their board is
 *       not moderating it. Either refusal is a 403 - the caller can already see the comment, which is
 *       exactly the case the 404-not-403 rule reserves 403 for.</li>
 * </ul>
 */
@Transactional
@Service
public class TaskCommentService {

    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 100;

    private final TaskCommentRepository comments;
    private final TaskRepository tasks;
    private final TaskCommentMapper mapper;
    private final BoardService boardService;
    private final BoardEventPublisher boardEvents;
    private final TaskActivityRecorder activityRecorder;
    private final Clock clock;

    @Autowired
    public TaskCommentService(TaskCommentRepository comments,
                              TaskRepository tasks,
                              TaskCommentMapper mapper,
                              BoardService boardService,
                              BoardEventPublisher boardEvents,
                              TaskActivityRecorder activityRecorder) {
        this(comments, tasks, mapper, boardService, boardEvents, activityRecorder, Clock.systemUTC());
    }

    TaskCommentService(TaskCommentRepository comments,
                       TaskRepository tasks,
                       TaskCommentMapper mapper,
                       BoardService boardService,
                       BoardEventPublisher boardEvents,
                       TaskActivityRecorder activityRecorder,
                       Clock clock) {
        this.comments = comments;
        this.tasks = tasks;
        this.mapper = mapper;
        this.boardService = boardService;
        this.boardEvents = boardEvents;
        this.activityRecorder = activityRecorder;
        this.clock = clock;
    }

    public TaskCommentResults thread(User caller, Integer taskId, Integer page, Integer size) {
        int wantedPage = page == null ? 0 : page;
        int wantedSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (wantedPage < 0 || wantedSize < 1 || wantedSize > MAX_PAGE_SIZE) {
            throw new GlobalException(ExceptionIdentifier.INVALID_COMMENT_REQUEST,
                    "page must be 0 or more and size must be between 1 and " + MAX_PAGE_SIZE);
        }

        var found = comments.findByTaskOrderByCreatedAtDescIdDesc(
                findTask(caller, taskId), PageRequest.of(wantedPage, wantedSize));
        return new TaskCommentResults(
                found.getContent().stream().map(mapper).toList(),
                wantedPage,
                wantedSize,
                found.getTotalElements(),
                found.getTotalPages());
    }

    public TaskCommentDto add(User caller, Integer taskId, TaskCommentRequest request) {
        var task = findTask(caller, taskId);
        boardService.requireWritable(caller, task.getBoard());

        var comment = new TaskComment();
        comment.setTask(task);
        comment.setAuthor(caller);
        comment.setBody(request.body().strip());
        comment.setCreatedAt(Instant.now(clock));

        var saved = comments.save(comment);
        activityRecorder.commented(caller, task);
        boardEvents.commentsChanged(task.getBoard());
        return mapper.apply(saved);
    }

    public TaskCommentDto edit(User caller, Integer taskId, Long commentId, TaskCommentRequest request) {
        var comment = findComment(caller, taskId, commentId);
        boardService.requireWritable(caller, comment.getTask().getBoard());
        if (!isAuthor(caller, comment)) {
            throw new GlobalException(ExceptionIdentifier.NOT_COMMENT_AUTHOR);
        }

        comment.setBody(request.body().strip());
        comment.setEditedAt(Instant.now(clock));

        var saved = comments.save(comment);
        boardEvents.commentsChanged(comment.getTask().getBoard());
        return mapper.apply(saved);
    }

    public void delete(User caller, Integer taskId, Long commentId) {
        var comment = findComment(caller, taskId, commentId);
        var board = boardService.requireWritable(caller, comment.getTask().getBoard());
        if (!isAuthor(caller, comment) && !board.isOwnedBy(caller)) {
            throw new GlobalException(ExceptionIdentifier.NOT_COMMENT_AUTHOR);
        }

        comments.delete(comment);
        boardEvents.commentsChanged(board);
    }

    /**
     * Everything said about a card that is being deleted. Called by {@code TaskService.deleteTask}
     * rather than left to a cascade, the same by-hand removal attachments get. No caller parameter:
     * the task lookup that found the task being deleted has already checked who is asking.
     */
    public void deleteAllFor(Task task) {
        var toDelete = comments.findByTask(task);
        if (!toDelete.isEmpty()) {
            comments.deleteAll(toDelete);
        }
    }

    /**
     * Every thread on a board being deleted, before its tasks go - {@code task_comments.task_id}
     * does not cascade, so without this a board with one comment could not be deleted, which is the
     * failure attachments had. Synchronous, inside {@code deleteBoard}'s transaction; see
     * {@link BoardTasksDeleting}.
     */
    @EventListener
    public void onBoardTasksDeleting(BoardTasksDeleting event) {
        if (event.tasks() == null || event.tasks().isEmpty()) {
            return;
        }
        var toDelete = comments.findByTaskIn(event.tasks());
        if (!toDelete.isEmpty()) {
            comments.deleteAll(toDelete);
        }
    }

    // ------------------------------------------------------------------ lookups ---

    /** The task, or a 404 that does not say whether it exists on somebody else's board. */
    private Task findTask(User caller, Integer taskId) {
        var task = tasks.findById(taskId).orElseThrow(() -> taskNotFound(taskId));
        if (task.getBoard() == null || !task.getBoard().isVisibleTo(caller)) {
            throw taskNotFound(taskId);
        }
        return task;
    }

    /**
     * A comment on the named task, on a task the caller can see. The task is checked first and the
     * comment matched against it - otherwise a comment id from another board would be reachable
     * through any task the caller can see, and the task in the path would be decoration.
     */
    private TaskComment findComment(User caller, Integer taskId, Long commentId) {
        var task = findTask(caller, taskId);
        var comment = comments.findById(commentId).orElseThrow(() -> commentNotFound(commentId));
        if (comment.getTask() == null || !task.getId().equals(comment.getTask().getId())) {
            throw commentNotFound(commentId);
        }
        return comment;
    }

    /**
     * By id, never by instance: the caller comes from the JWT filter and the author from the
     * persistence context, and {@code User} inherits identity equality - the trap
     * {@code Board.isVisibleTo} documents.
     */
    private static boolean isAuthor(User caller, TaskComment comment) {
        return comment.getAuthor() != null && caller != null
                && Objects.equals(comment.getAuthor().getId(), caller.getId());
    }

    private static GlobalException taskNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.TASK_NOT_FOUND, "Task not found with id: " + id);
    }

    private static GlobalException commentNotFound(Long id) {
        return new GlobalException(ExceptionIdentifier.COMMENT_NOT_FOUND, "Comment not found with id: " + id);
    }
}
