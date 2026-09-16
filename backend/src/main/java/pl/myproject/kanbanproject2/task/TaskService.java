package pl.myproject.kanbanproject2.task;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.Row;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.activity.TaskActivityRecorder;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistory;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryDto;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryMapper;
import pl.myproject.kanbanproject2.task.attachment.TaskAttachmentService;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every method here takes the caller, and every lookup goes through {@link #findTask(User, Integer)}
 * — the one place that decides whether the caller may see a task at all. Passing the caller in
 * rather than reading it out of the security context is what lets {@code BoardScopedRoutesTest}
 * check by reflection that no route was left without one.
 */
@RequiredArgsConstructor
@Transactional
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final TaskMapper taskMapper;
    private final UserService userService;
    private final TaskColumnHistoryRepository taskColumnHistoryRepository;
    private final TaskColumnHistoryMapper historyMapper;
    private final ColumnRepository columnRepository;
    private final RowRepository rowRepository;
    private final BoardService boardService;
    private final DeadlineNotifier deadlineNotifier;
    private final TaskAttachmentService attachmentService;
    private final TaskActivityRecorder activityRecorder;
    private final BoardEventPublisher boardEvents;

    public TaskDto addTask(User caller, Integer boardId, CreateTaskRequest request) {
        var board = boardService.resolve(caller, boardId);
        var column = request.column() != null ? findColumn(caller, board, request.column().id()) : null;
        var row = request.row() != null ? findRow(caller, board, request.row().id()) : null;

        var task = new Task();
        task.setBoard(board);
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setLabels(request.labels() != null ? new HashSet<>(request.labels()) : new HashSet<>());
        task.setColumn(column);
        task.setRow(row);
        task.setPosition(request.position() != null
                ? request.position()
                : nextPositionIn(board, column, row));
        applyDeadline(task, request.deadline());

        var savedTask = taskRepository.save(task);

        if (savedTask.getColumn() != null) {
            saveTaskColumnHistory(savedTask, savedTask.getColumn());
        }
        activityRecorder.created(caller, savedTask);
        boardEvents.tasksChanged(savedTask.getBoard());
        return taskMapper.apply(savedTask);
    }

    public List<TaskDto> getAllTasks(User caller, Integer boardId) {
        var board = boardService.resolve(caller, boardId);
        return taskRepository.findByBoardOrderByIdAsc(board).stream()
                .map(taskMapper)
                .sorted(POSITION_ORDER)
                .toList();
    }

    /**
     * The tasks on one board that match a search, one page at a time. Unlike the unpaged board
     * listing above, an empty query here matches the whole board, so this route bounds the answer
     * itself (PERF-02). It runs as two queries: ids are paged and ordered in SQL, then the matching
     * rows are fetched by id and put back into that order, since {@code findByIdIn} makes no
     * ordering promise of its own.
     */
    public TaskSearchResults searchTasks(User caller, Integer boardId, TaskSearchCriteria criteria) {
        var board = boardService.resolve(caller, boardId);

        // Each filter arrives as "is it off" and "what it is", never as a null - see
        // TaskRepository.findMatchingIds for the reason, which only a real database can show you.
        var matching = taskRepository.findMatchingIds(
                board,
                criteria.text() == null,
                criteria.textOrPlaceholder(),
                criteria.completed() == null,
                criteria.completedOrPlaceholder(),
                criteria.deadlineFrom() == null,
                criteria.deadlineFromOrPlaceholder(),
                criteria.deadlineTo() == null,
                criteria.deadlineToOrPlaceholder(),
                criteria.labels().isEmpty(),
                criteria.labelsOrPlaceholder(),
                criteria.assignees().isEmpty(),
                criteria.assigneesOrPlaceholder(),
                PageRequest.of(criteria.page(), criteria.size()));

        var ids = matching.getContent();
        // Past the last page, or a board with nothing on it. Asking findByIdIn for an empty list
        // is a query with an empty IN, which is the one thing the criteria go out of their way to
        // avoid handing the database elsewhere.
        List<TaskDto> found = ids.isEmpty()
                ? List.of()
                : inTheOrderOf(ids, taskRepository.findByIdIn(ids));

        return new TaskSearchResults(found, criteria.page(), criteria.size(),
                matching.getTotalElements(), matching.getTotalPages());
    }

    /** Maps the page's rows and puts them back in the order the paged query chose. */
    private List<TaskDto> inTheOrderOf(List<Integer> ids, List<Task> rows) {
        var byId = rows.stream().collect(Collectors.toMap(Task::getId, taskMapper));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /**
     * Rows written before positions were scoped to a cell can still carry a null one, and a task
     * that cannot be placed is no reason to fail the whole board listing - it sorts last instead.
     */
    private static final Comparator<TaskDto> POSITION_ORDER =
            Comparator.comparing(TaskDto::position, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Saves a task, tells the board's other viewers, and maps it. Every mutation routes through
     * here rather than mapping the repository's return value directly, so a forgotten announcement
     * becomes a different method call — one {@code BoardEventCoverageTest} catches — instead of a
     * silently missing line.
     */
    private TaskDto saveAndAnnounce(Task task) {
        var saved = taskRepository.save(task);
        boardEvents.tasksChanged(saved.getBoard());
        return taskMapper.apply(saved);
    }

    /**
     * The next free position in one cell of the board, scoped by board, column and row (both
     * optional) so it means an ordinal within the cell rather than {@code count() + 1} over the
     * whole table — which collides after a delete and under concurrent creates.
     */
    private int nextPositionIn(Board board, Column column, Row row) {
        return taskRepository.findMaxPosition(
                board.getId(),
                column == null ? null : column.getId(),
                row == null ? null : row.getId()).orElse(0) + 1;
    }

    public void deleteTask(User caller, Integer id) {
        var task = findTask(caller, id);

        // Written before the row goes, then detached from it: the deletion record has to outlive
        // its subject, and nothing here cascades so a foreign key can't take the record down with it.
        activityRecorder.deleted(caller, task);
        activityRecorder.detachFrom(task);

        taskColumnHistoryRepository.deleteAll(taskColumnHistoryRepository.findByTaskOrderByChangedAtDesc(task));

        // A service call rather than a cascade: only this can take the blobs with the rows, and a
        // foreign-key cascade would leave every blob orphaned with nothing left that knows its name.
        attachmentService.deleteAllFor(task);

        if (task.getChildTasks() != null && !task.getChildTasks().isEmpty()) {
            for (Task child : task.getChildTasks()) {
                child.setParentTask(null);
                taskRepository.save(child);
            }
            task.getChildTasks().clear();
        }

        taskRepository.delete(task);
        boardEvents.tasksChanged(task.getBoard());
    }

    public TaskDto getTaskById(User caller, Integer id) {
        return taskMapper.apply(findTask(caller, id));
    }

    public TaskDto patchTask(User caller, Integer id, PatchTaskRequest request) {
        var existingTask = findTask(caller, id);
        requireCurrentVersion(existingTask, request.version());
        var board = existingTask.getBoard();

        if (request.title().isPresent()) {
            var title = request.title().get();
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("A task title cannot be blank");
            }
            existingTask.setTitle(title);
        }

        if (request.column().isPresent()) {
            var column = request.column().get();
            moveToColumn(caller, existingTask, column == null ? null : findColumn(caller, board, column.id()));
        }

        if (request.position().isPresent()) {
            var position = request.position().get();
            if (position == null) {
                // getAllTasks sorts on it, so a null position would break the whole board listing.
                throw new IllegalArgumentException("A task position cannot be cleared");
            }
            existingTask.setPosition(position);
        }

        if (request.row().isPresent()) {
            var row = request.row().get();
            existingTask.setRow(row == null ? null : findRow(caller, board, row.id()));
        }

        if (request.labels().isPresent()) {
            var labels = request.labels().get();
            existingTask.setLabels(labels == null ? new HashSet<>() : new HashSet<>(labels));
        }

        if (request.description().isPresent()) {
            existingTask.setDescription(request.description().get());
        }

        if (request.deadline().isPresent()) {
            applyDeadline(existingTask, request.deadline().get());
        }

        return saveAndAnnounce(existingTask);
    }

    /**
     * Refuses a PATCH based on a stale copy of the task. The {@code @Version} column catches
     * overlapping transactions but not the slower race — an editing form saved after another
     * member's write has already committed cleanly, so Hibernate sees nothing to object to;
     * comparing the version the caller last saw closes that gap, while sending no version keeps a
     * drag (never stale in this sense) untouched. Raises the same exception Hibernate uses for the
     * transaction-level conflict, so it lands on the existing 409 {@code CONCURRENT_MODIFICATION}
     * handler.
     */
    private void requireCurrentVersion(Task task, Integer seenVersion) {
        if (seenVersion != null && !seenVersion.equals(task.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Task.class, task.getId());
        }
    }

    /**
     * Records the move for the history report and tolerates a {@code null} column — a task can be
     * taken off the board, with no arrival to record. Only the arrival is recorded: writing the
     * departure too would put two rows on the same instant with an arbitrary sort order, corrupting
     * the per-column time calculation the report derives from the gap between arrivals.
     */
    private void moveToColumn(User caller, Task task, Column newColumn) {
        var currentColumn = task.getColumn();
        boolean unchanged = currentColumn == null
                ? newColumn == null
                : newColumn != null && currentColumn.getId().equals(newColumn.getId());
        if (unchanged) {
            return;
        }

        task.setColumn(newColumn);
        if (newColumn != null) {
            saveTaskColumnHistory(task, newColumn);
            // Both records of the same move are written together so they can't drift: the history
            // row is an interval series with no actor, TaskActivity is the actor log.
            activityRecorder.moved(caller, task, newColumn.getName());
        }
    }

    /**
     * Keeps {@code expired} consistent with the deadline it describes. The scheduled sweep only
     * looks at tasks that still have a deadline, so clearing one would otherwise leave the flag
     * stuck on whatever it was when the deadline was removed.
     */
    private void applyDeadline(Task task, LocalDateTime deadline) {
        task.setDeadline(deadline);
        task.setExpired(deadline != null && deadline.isBefore(LocalDateTime.now()));
    }

    private void saveTaskColumnHistory(Task task, Column column) {
        var taskHistory = taskColumnHistoryRepository.findByTaskOrderByChangedAtDesc(task);
        var nextHistoryOrder = 0;

        if (!taskHistory.isEmpty()) {
            var lastHistory = taskHistory.getFirst();
            nextHistoryOrder = (lastHistory.getHistoryOrder() != null ? lastHistory.getHistoryOrder() : 0) + 1;
        }

        var history = new TaskColumnHistory(task, column);
        history.setHistoryOrder(nextHistoryOrder);
        taskColumnHistoryRepository.save(history);
    }

    public List<TaskColumnHistory> getTaskColumnHistory(User caller, Integer taskId) {
        var task = findTask(caller, taskId);
        return taskColumnHistoryRepository.findByTaskOrderByChangedAtDesc(task);
    }

    public List<TaskColumnHistoryDto> getTaskColumnHistoryDTOs(User caller, Integer taskId) {
        return getTaskColumnHistory(caller, taskId).stream().map(historyMapper::toDTO).toList();
    }

    /**
     * Puts a member of the board on one of its tasks. The assignee is checked against the task's
     * board rather than merely looked up by id — otherwise any account in the deployment could be
     * pinned to work on a board it cannot see, and counted against its WIP limit. A non-member
     * answers as not found, per {@link BoardService}.
     */
    public TaskDto assignUserToTask(User caller, Integer taskId, Integer userId) {
        var task = findTask(caller, taskId);
        var user = findBoardMember(task.getBoard(), userId);

        if (!userService.checkWipStatus(userId)) {
            throw new GlobalException(ExceptionIdentifier.USER_WIP_LIMIT_EXCEEDED);
        }

        task.getUsers().add(user);
        user.getTasks().add(task);

        userRepository.save(user);
        activityRecorder.assigned(caller, task, user);
        return saveAndAnnounce(task);
    }

    /**
     * Takes somebody off a task. Deliberately not restricted to current members: a user removed
     * from the board, or deleted and recreated, still has to be removable from the work.
     */
    public TaskDto removeUserFromTask(User caller, Integer taskId, Integer userId) {
        var task = findTask(caller, taskId);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND,
                        "User not found with id: " + userId));

        task.getUsers().remove(user);
        user.getTasks().remove(task);

        userRepository.save(user);
        activityRecorder.unassigned(caller, task, user);
        return saveAndAnnounce(task);
    }

    public TaskDto updateTaskPosition(User caller, Integer id, Integer position) {
        var task = findTask(caller, id);
        task.setPosition(position);
        return saveAndAnnounce(task);
    }

    /**
     * Renumbers one cell in a single transaction, from the ids in the order they should read.
     * Reordering used to be one PATCH per card, which merely wasted calls until tasks gained a
     * {@code @Version}: a card somebody else moved then made one PATCH a 409 while the rest stayed
     * applied, leaving the board half old and half new. One transaction means a stale write rolls
     * back the whole batch instead. Positions are the index within exactly the ids given, so a
     * caller sending part of a cell renumbers only that part.
     */
    public List<TaskDto> reorderTasks(User caller, List<Integer> orderedIds) {
        requireDistinct(orderedIds, "task");

        var tasks = orderedIds.stream().map(id -> findTask(caller, id)).toList();
        requireOneCell(tasks);

        var reordered = new ArrayList<TaskDto>(tasks.size());
        for (int position = 0; position < tasks.size(); position++) {
            var task = tasks.get(position);
            task.setPosition(position);
            reordered.add(saveAndAnnounce(task));
        }
        return reordered;
    }

    /**
     * Every task in the batch has to sit in the same column and swimlane. Both are nullable, so
     * this compares ids and treats "no column" as a cell of its own rather than a wildcard —
     * comparing entities directly would repeat the bug {@code Board.isVisibleTo} had before it
     * compared ids.
     */
    private void requireOneCell(List<Task> tasks) {
        var first = tasks.get(0);
        Integer columnId = first.getColumn() == null ? null : first.getColumn().getId();
        Integer rowId = first.getRow() == null ? null : first.getRow().getId();

        for (Task task : tasks) {
            var taskColumn = task.getColumn() == null ? null : task.getColumn().getId();
            var taskRow = task.getRow() == null ? null : task.getRow().getId();
            boardService.requireSameBoard(first.getBoard(), task.getBoard());
            if (!Objects.equals(columnId, taskColumn) || !Objects.equals(rowId, taskRow)) {
                throw new GlobalException(ExceptionIdentifier.INVALID_REORDER,
                        "Task " + task.getId() + " is not in the same cell as task " + first.getId()
                                + "; move it first, then reorder");
            }
        }
    }

    private static void requireDistinct(List<Integer> ids, String what) {
        if (ids.size() != new HashSet<>(ids).size()) {
            throw new GlobalException(ExceptionIdentifier.INVALID_REORDER,
                    "The same " + what + " appears more than once in the requested order");
        }
    }

    public TaskDto addLabelToTask(User caller, Integer taskId, String label) {
        var task = findTask(caller, taskId);
        if (task.getLabels() == null) {
            task.setLabels(new HashSet<>());
        }
        task.getLabels().add(label);
        return saveAndAnnounce(task);
    }

    public TaskDto removeLabelFromTask(User caller, Integer taskId, String label) {
        var task = findTask(caller, taskId);
        if (task.getLabels() != null) {
            task.getLabels().remove(label);
            return saveAndAnnounce(task);
        }
        return taskMapper.apply(task);
    }

    public TaskDto updateTaskLabels(User caller, Integer taskId, Set<String> labels) {
        var task = findTask(caller, taskId);
        task.setLabels(labels);
        return saveAndAnnounce(task);
    }

    public Set<String> getAllLabels(User caller, Integer boardId) {
        return taskRepository.findDistinctLabels(boardService.resolve(caller, boardId));
    }

    public TaskDto assignParentTask(User caller, Integer childTaskId, Integer parentTaskId) {
        var childTask = findTask(caller, childTaskId);
        var parentTask = taskRepository.findById(parentTaskId)
                .orElseThrow(() -> parentNotFound(parentTaskId));

        // A cross-board dependency would make one board's progress wait on work its members can't
        // see, and let the un-completion cascade reach a board the caller may not be on.
        if (!parentTask.getBoard().isVisibleTo(caller)
                || !parentTask.getBoard().getId().equals(childTask.getBoard().getId())) {
            throw parentNotFound(parentTaskId);
        }

        if (wouldCreateCycle(childTask, parentTask)) {
            throw new GlobalException(ExceptionIdentifier.CYCLIC_TASK_DEPENDENCY);
        }

        childTask.setParentTask(parentTask);
        parentTask.getChildTasks().add(childTask);

        taskRepository.save(parentTask);
        return saveAndAnnounce(childTask);
    }

    public TaskDto removeParentTask(User caller, Integer childTaskId) {
        var childTask = findTask(caller, childTaskId);
        if (childTask.getParentTask() != null) {
            var parentTask = childTask.getParentTask();
            parentTask.getChildTasks().remove(childTask);
            childTask.setParentTask(null);
            taskRepository.save(parentTask);
        }
        return saveAndAnnounce(childTask);
    }

    public List<TaskDto> getChildTasks(User caller, Integer taskId) {
        return findTask(caller, taskId).getChildTasks().stream().map(taskMapper).toList();
    }

    public TaskDto getParentTask(User caller, Integer taskId) {
        var task = findTask(caller, taskId);
        if (task.getParentTask() == null) {
            throw new GlobalException(ExceptionIdentifier.PARENT_TASK_NOT_SET);
        }
        return taskMapper.apply(task.getParentTask());
    }

    /**
     * Returns true if making {@code newParent} the parent of {@code child} would form a cycle,
     * i.e. {@code newParent} is already a descendant of {@code child}. The visited set guards
     * against a cycle already present in the data turning a later assignment into a
     * {@link StackOverflowError}.
     */
    private boolean wouldCreateCycle(Task child, Task newParent) {
        return isDescendantOf(child, newParent, new HashSet<>());
    }

    private boolean isDescendantOf(Task candidate, Task newParent, Set<Integer> visited) {
        if (candidate.getId().equals(newParent.getId())) {
            return true;
        }
        if (!visited.add(candidate.getId())) {
            return false;
        }
        return candidate.getChildTasks().stream()
                .anyMatch(child -> isDescendantOf(child, newParent, visited));
    }

    public boolean canTaskBeCompleted(User caller, Integer taskId) {
        return canTaskBeCompleted(findTask(caller, taskId));
    }

    /**
     * The rule itself, over a task already fetched and checked — so a caller like
     * {@link #updateTaskCompletion} that already paid for the lookup and its access check doesn't
     * pay twice.
     */
    private boolean canTaskBeCompleted(Task task) {
        return task.getParentTask() == null || task.getParentTask().isCompleted();
    }

    public TaskDto updateTaskCompletion(User caller, Integer taskId, boolean completed) {
        var task = findTask(caller, taskId);

        if (completed && !canTaskBeCompleted(task)) {
            throw new GlobalException(ExceptionIdentifier.PARENT_TASK_NOT_COMPLETED);
        }

        boolean changed = task.isCompleted() != completed;
        task.setCompleted(completed);
        if (!completed) {
            updateDependentTasksCompletion(task);
        }
        // Only a real change. Ticking a box that is already ticked is a request, not an event, and
        // a feed that records it fills with entries nobody performed.
        if (changed) {
            activityRecorder.completionChanged(caller, task, completed);
        }
        return saveAndAnnounce(task);
    }

    private void updateDependentTasksCompletion(Task parentTask) {
        updateDependentTasksCompletion(parentTask, new HashSet<>());
    }

    /** Cascades the un-completion downward, visiting each task once. See {@link #wouldCreateCycle}. */
    private void updateDependentTasksCompletion(Task parentTask, Set<Integer> visited) {
        if (!visited.add(parentTask.getId())) {
            return;
        }
        parentTask.getChildTasks().forEach(childTask -> {
            if (childTask.isCompleted()) {
                childTask.setCompleted(false);
                taskRepository.save(childTask);
                updateDependentTasksCompletion(childTask, visited);
            }
        });
    }

    public List<TaskDto> getDailyFocusTasks(User caller, Integer boardId) {
        var board = boardService.resolve(caller, boardId);
        return taskRepository.findByBoardAndDailyFocusTrue(board).stream().map(taskMapper).toList();
    }

    public TaskDto setDailyFocus(User caller, Integer taskId, boolean dailyFocus) {
        var task = findTask(caller, taskId);
        if (task.isDailyFocus() == dailyFocus) {
            return taskMapper.apply(task);
        }
        task.setDailyFocus(dailyFocus);
        return saveAndAnnounce(task);
    }

    /**
     * The one method here with no caller — it runs on a timer for the whole deployment rather than
     * on behalf of a user. Every flag is written before the notification mail goes out, so a slow
     * or unreachable provider can't leave {@code expired} half-updated; only the crossing into
     * expired is mailed.
     *
     * <p>It claims the rows it's about to change via
     * {@link TaskRepository#claimTasksCrossingDeadline}'s {@code FOR UPDATE SKIP LOCKED}, so two
     * schedulers can't both flag and mail the same task. The class-level {@code @Transactional} is
     * what holds that claim for the sweep's whole duration — remove it and the lock releases before
     * the claim protects anything. The claim selects only rows whose flag disagrees with their
     * deadline, which also keeps a half-hourly sweep from reading every deadline in the deployment.
     */
    @Scheduled(fixedRate = 1800000)
    public void checkAllTasksDeadlines() {
        var now = LocalDateTime.now();
        var claimed = taskRepository.claimTasksCrossingDeadline(now);
        if (claimed.isEmpty()) {
            return;
        }

        var newlyExpired = new ArrayList<Task>();
        for (Task task : taskRepository.findByIdIn(claimed)) {
            // The claim already decided this row disagrees with its deadline; the comparison here
            // is what says in which direction, on the same instant the claim was made with.
            boolean isExpired = task.getDeadline().isBefore(now);
            task.setExpired(isExpired);
            taskRepository.save(task);
            boardEvents.tasksChanged(task.getBoard());
            if (isExpired) {
                newlyExpired.add(task);
            }
        }

        newlyExpired.forEach(deadlineNotifier::notifyExpired);
    }

    /**
     * Looks a task up and refuses to hand it back unless the caller is on its board. Every public
     * method funnels through here, since a check that must be remembered at each call site is one
     * that eventually gets forgotten at one of them.
     */
    private Task findTask(User caller, Integer id) {
        var task = taskRepository.findById(id).orElseThrow(() -> taskNotFound(id));
        if (!task.getBoard().isVisibleTo(caller)) {
            throw taskNotFound(id);
        }
        return task;
    }

    private Column findColumn(User caller, Board board, Integer id) {
        var column = columnRepository.findById(id).orElseThrow(() -> columnNotFound(id));
        if (!column.getBoard().isVisibleTo(caller)) {
            throw columnNotFound(id);
        }
        boardService.requireSameBoard(board, column.getBoard());
        return column;
    }

    private Row findRow(User caller, Board board, Integer id) {
        var row = rowRepository.findById(id).orElseThrow(() -> rowNotFound(id));
        if (!row.getBoard().isVisibleTo(caller)) {
            throw rowNotFound(id);
        }
        boardService.requireSameBoard(board, row.getBoard());
        return row;
    }

    private User findBoardMember(Board board, Integer userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> userNotFound(userId));
        if (!board.isVisibleTo(user)) {
            throw userNotFound(userId);
        }
        return user;
    }

    private GlobalException taskNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.TASK_NOT_FOUND,
                "Task not found with id: " + id);
    }

    private GlobalException parentNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.PARENT_TASK_NOT_FOUND,
                "Parent task not found with id: " + id);
    }

    private GlobalException columnNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.COLUMN_NOT_FOUND,
                "Column not found with id: " + id);
    }

    private GlobalException rowNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.ROW_NOT_FOUND,
                "Row not found with id: " + id);
    }

    private GlobalException userNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.USER_NOT_FOUND,
                "User not found with id: " + id);
    }
}
