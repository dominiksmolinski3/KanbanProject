package pl.myproject.kanbanproject2.task;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.Row;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistory;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryDto;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryMapper;
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
     * Rows written before positions were scoped to a cell can still carry a null one, and a task
     * that cannot be placed is no reason to fail the whole board listing - it sorts last instead.
     */
    private static final Comparator<TaskDto> POSITION_ORDER =
            Comparator.comparing(TaskDto::position, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * The next free position in one cell of the board.
     *
     * <p>This used to be {@code count() + 1} over the whole table, which collides two ways: the
     * count drops after any delete, so the next create reuses a number that is still in use, and
     * two concurrent creates read the same count. Scoping it to the cell also makes the number
     * mean what the board renders - an ordinal within the cell, not a row number.
     *
     * <p>The board is part of the key because the column and the swimlane are both optional: a
     * task in neither still has to be numbered, and without the board every such task in the
     * deployment would be drawing from one shared sequence.
     */
    private int nextPositionIn(Board board, Column column, Row row) {
        return taskRepository.findMaxPosition(
                board.getId(),
                column == null ? null : column.getId(),
                row == null ? null : row.getId()).orElse(0) + 1;
    }

    public void deleteTask(User caller, Integer id) {
        var task = findTask(caller, id);

        taskColumnHistoryRepository.deleteAll(taskColumnHistoryRepository.findByTaskOrderByChangedAtDesc(task));

        if (task.getChildTasks() != null && !task.getChildTasks().isEmpty()) {
            for (Task child : task.getChildTasks()) {
                child.setParentTask(null);
                taskRepository.save(child);
            }
            task.getChildTasks().clear();
        }

        taskRepository.delete(task);
    }

    public TaskDto getTaskById(User caller, Integer id) {
        return taskMapper.apply(findTask(caller, id));
    }

    public TaskDto patchTask(User caller, Integer id, PatchTaskRequest request) {
        var existingTask = findTask(caller, id);
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
            moveToColumn(existingTask, column == null ? null : findColumn(caller, board, column.id()));
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

        return taskMapper.apply(taskRepository.save(existingTask));
    }

    /**
     * Records the move the way the history report expects, and tolerates a column of {@code null}
     * — a task can be taken off the board, and there is no arrival to record when it is.
     *
     * <p>Only the arrival is recorded. Writing the departure as well put two rows on the same
     * instant, and the report orders on nothing but that instant: which of the pair sorts first
     * is arbitrary, and one of the two orders charges the whole of the next column's time to the
     * previous one. Time in a column is the gap to the next arrival, which needs a single row.
     */
    private void moveToColumn(Task task, Column newColumn) {
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
     * Puts a member of the board on one of its tasks.
     *
     * <p>The assignee is checked against the task's board rather than merely looked up by id.
     * Without that, any id in the deployment could be written onto a task — pinning work on
     * somebody who cannot open the board, and counting it against their WIP limit. An account that
     * is not on the board answers as one that does not exist, for the reason given in
     * {@link BoardService}.
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
        return taskMapper.apply(taskRepository.save(task));
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
        return taskMapper.apply(taskRepository.save(task));
    }

    public TaskDto updateTaskPosition(User caller, Integer id, Integer position) {
        var task = findTask(caller, id);
        task.setPosition(position);
        return taskMapper.apply(taskRepository.save(task));
    }

    /**
     * Renumbers one cell in a single transaction, from the ids in the order they should read.
     *
     * <p>The client has always reordered a cell by sending one PATCH per card in it. That was
     * merely wasteful until the tasks gained a {@code @Version}: now a card somebody else has
     * moved makes one of those PATCHes a 409, and the ones that already succeeded stay applied -
     * so a failed reorder leaves the board in an order nobody asked for, half old and half new.
     * One call is one transaction, and a stale write in it rolls back every position in the batch.
     *
     * <p>Positions are the index in the list, over exactly the ids given. A caller who sends part
     * of a cell renumbers only that part, which is the same thing the per-task route does one call
     * at a time; the client sends the whole cell.
     */
    public List<TaskDto> reorderTasks(User caller, List<Integer> orderedIds) {
        requireDistinct(orderedIds, "task");

        var tasks = orderedIds.stream().map(id -> findTask(caller, id)).toList();
        requireOneCell(tasks);

        var reordered = new ArrayList<TaskDto>(tasks.size());
        for (int position = 0; position < tasks.size(); position++) {
            var task = tasks.get(position);
            task.setPosition(position);
            reordered.add(taskMapper.apply(taskRepository.save(task)));
        }
        return reordered;
    }

    /**
     * Every task in the batch has to sit in the same column and the same swimlane.
     *
     * <p>Both are nullable - a task can be off the board entirely - so this compares ids and treats
     * "no column" as a cell of its own rather than as a wildcard. Comparing the entities would go
     * wrong the same way {@code Board.isVisibleTo} did before it compared ids.
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
        return taskMapper.apply(taskRepository.save(task));
    }

    public TaskDto removeLabelFromTask(User caller, Integer taskId, String label) {
        var task = findTask(caller, taskId);
        if (task.getLabels() != null) {
            task.getLabels().remove(label);
            return taskMapper.apply(taskRepository.save(task));
        }
        return taskMapper.apply(task);
    }

    public TaskDto updateTaskLabels(User caller, Integer taskId, Set<String> labels) {
        var task = findTask(caller, taskId);
        task.setLabels(labels);
        return taskMapper.apply(taskRepository.save(task));
    }

    public Set<String> getAllLabels(User caller, Integer boardId) {
        return taskRepository.findDistinctLabels(boardService.resolve(caller, boardId));
    }

    public TaskDto assignParentTask(User caller, Integer childTaskId, Integer parentTaskId) {
        var childTask = findTask(caller, childTaskId);
        var parentTask = taskRepository.findById(parentTaskId)
                .orElseThrow(() -> parentNotFound(parentTaskId));

        /*
         * A dependency across two boards would make one board's progress wait on work the other
         * board's members cannot see, and would let the un-completion cascade reach into a board
         * the caller may not even be on. An unreachable parent answers as a missing one.
         */
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
        return taskMapper.apply(taskRepository.save(childTask));
    }

    public TaskDto removeParentTask(User caller, Integer childTaskId) {
        var childTask = findTask(caller, childTaskId);
        if (childTask.getParentTask() != null) {
            var parentTask = childTask.getParentTask();
            parentTask.getChildTasks().remove(childTask);
            childTask.setParentTask(null);
            taskRepository.save(parentTask);
        }
        return taskMapper.apply(taskRepository.save(childTask));
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
     * i.e. {@code newParent} is already a descendant of {@code child}.
     *
     * <p>The walk carries the ids it has already visited. That guard is what stops a cycle which
     * is <em>already</em> in the data - written before this check existed, or by hand - from
     * turning every later parent assignment into a {@link StackOverflowError}.
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
     * The rule itself, over a task already fetched and already checked. It used to take an id and
     * re-read the task, which meant {@link #updateTaskCompletion} paid for a second lookup and,
     * once the lookup carried an access check, would have paid for that twice too.
     */
    private boolean canTaskBeCompleted(Task task) {
        return task.getParentTask() == null || task.getParentTask().isCompleted();
    }

    public TaskDto updateTaskCompletion(User caller, Integer taskId, boolean completed) {
        var task = findTask(caller, taskId);

        if (completed && !canTaskBeCompleted(task)) {
            throw new GlobalException(ExceptionIdentifier.PARENT_TASK_NOT_COMPLETED);
        }

        task.setCompleted(completed);
        if (!completed) {
            updateDependentTasksCompletion(task);
        }
        return taskMapper.apply(taskRepository.save(task));
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
        return taskMapper.apply(taskRepository.save(task));
    }

    /**
     * The one method here that takes no caller, because it has none: it runs on a timer, on behalf
     * of the deployment rather than of a user, and every board's deadlines have to be swept.
     *
     * <p>Every flag is written first and the mail goes out afterwards, so a slow or unreachable
     * mail provider cannot leave the {@code expired} column half-updated. Only the crossing into
     * expired is notified; a task whose deadline was pushed back goes quiet without a second mail.
     */
    @Scheduled(fixedRate = 1800000)
    public void checkAllTasksDeadlines() {
        var tasksWithDeadline = taskRepository.findAllByDeadlineIsNotNull();
        var now = LocalDateTime.now();
        var newlyExpired = new ArrayList<Task>();

        for (Task task : tasksWithDeadline) {
            boolean wasExpired = task.isExpired();
            boolean isExpired = task.getDeadline().isBefore(now);
            if (wasExpired != isExpired) {
                task.setExpired(isExpired);
                taskRepository.save(task);
                if (isExpired) {
                    newlyExpired.add(task);
                }
            }
        }

        newlyExpired.forEach(deadlineNotifier::notifyExpired);
    }

    /**
     * Looks a task up and refuses to hand it back unless the caller is on its board.
     *
     * <p>Every public method above goes through here, which is the point: a check that has to be
     * remembered at each call site is one that will eventually be forgotten at one of them. A task
     * on another board answers as a task that does not exist.
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
