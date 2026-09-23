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

    public void deleteAllFor(Task task) {
        var toDelete = comments.findByTask(task);
        if (!toDelete.isEmpty()) {
            comments.deleteAll(toDelete);
        }
    }

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

    private Task findTask(User caller, Integer taskId) {
        var task = tasks.findById(taskId).orElseThrow(() -> taskNotFound(taskId));
        if (task.getBoard() == null || !task.getBoard().isVisibleTo(caller)) {
            throw taskNotFound(taskId);
        }
        return task;
    }

    private TaskComment findComment(User caller, Integer taskId, Long commentId) {
        var task = findTask(caller, taskId);
        var comment = comments.findById(commentId).orElseThrow(() -> commentNotFound(commentId));
        if (comment.getTask() == null || !task.getId().equals(comment.getTask().getId())) {
            throw commentNotFound(commentId);
        }
        return comment;
    }

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
