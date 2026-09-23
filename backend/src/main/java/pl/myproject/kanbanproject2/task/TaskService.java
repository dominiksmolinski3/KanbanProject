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
import pl.myproject.kanbanproject2.task.comment.TaskCommentService;
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
    private final TaskCommentService commentService;

    public TaskDto addTask(User caller, Integer boardId, CreateTaskRequest request) {
        var board = boardService.resolve(caller, boardId);
        boardService.requireWritable(caller, board);
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

    public TaskSearchResults searchTasks(User caller, Integer boardId, TaskSearchCriteria criteria) {
        var board = boardService.resolve(caller, boardId);

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
        List<TaskDto> found = ids.isEmpty()
                ? List.of()
                : inTheOrderOf(ids, taskRepository.findByIdIn(ids));

        return new TaskSearchResults(found, criteria.page(), criteria.size(),
                matching.getTotalElements(), matching.getTotalPages());
    }

    private List<TaskDto> inTheOrderOf(List<Integer> ids, List<Task> rows) {
        var byId = rows.stream().collect(Collectors.toMap(Task::getId, taskMapper));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    private static final Comparator<TaskDto> POSITION_ORDER =
            Comparator.comparing(TaskDto::position, Comparator.nullsLast(Comparator.naturalOrder()));

    private TaskDto saveAndAnnounce(Task task) {
        var saved = taskRepository.save(task);
        boardEvents.tasksChanged(saved.getBoard());
        return taskMapper.apply(saved);
    }

    private int nextPositionIn(Board board, Column column, Row row) {
        return taskRepository.findMaxPosition(
                board.getId(),
                column == null ? null : column.getId(),
                row == null ? null : row.getId()).orElse(0) + 1;
    }

    public void deleteTask(User caller, Integer id) {
        var task = findTask(caller, id);
        boardService.requireWritable(caller, task.getBoard());

        activityRecorder.deleted(caller, task);
        activityRecorder.detachFrom(task);

        taskColumnHistoryRepository.deleteAll(taskColumnHistoryRepository.findByTaskOrderByChangedAtDesc(task));

        attachmentService.deleteAllFor(task);
        commentService.deleteAllFor(task);

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
        boardService.requireWritable(caller, existingTask.getBoard());
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

    private void requireCurrentVersion(Task task, Integer seenVersion) {
        if (seenVersion != null && !seenVersion.equals(task.getVersion())) {
            throw new ObjectOptimisticLockingFailureException(Task.class, task.getId());
        }
    }

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
            activityRecorder.moved(caller, task, newColumn.getName());
        }
    }

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

    public TaskDto assignUserToTask(User caller, Integer taskId, Integer userId) {
        var task = findTask(caller, taskId);
        boardService.requireWritable(caller, task.getBoard());
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

    public TaskDto removeUserFromTask(User caller, Integer taskId, Integer userId) {
        var task = findTask(caller, taskId);
        boardService.requireWritable(caller, task.getBoard());
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
        boardService.requireWritable(caller, task.getBoard());
        task.setPosition(position);
        return saveAndAnnounce(task);
    }

    public List<TaskDto> reorderTasks(User caller, List<Integer> orderedIds) {
        requireDistinct(orderedIds, "task");

        var tasks = orderedIds.stream().map(id -> findTask(caller, id)).toList();
        boardService.requireWritable(caller, tasks.get(0).getBoard());
        requireOneCell(tasks);

        var reordered = new ArrayList<TaskDto>(tasks.size());
        for (int position = 0; position < tasks.size(); position++) {
            var task = tasks.get(position);
            task.setPosition(position);
            reordered.add(saveAndAnnounce(task));
        }
        return reordered;
    }

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
        boardService.requireWritable(caller, task.getBoard());
        if (task.getLabels() == null) {
            task.setLabels(new HashSet<>());
        }
        task.getLabels().add(label);
        return saveAndAnnounce(task);
    }

    public TaskDto removeLabelFromTask(User caller, Integer taskId, String label) {
        var task = findTask(caller, taskId);
        boardService.requireWritable(caller, task.getBoard());
        if (task.getLabels() != null) {
            task.getLabels().remove(label);
            return saveAndAnnounce(task);
        }
        return taskMapper.apply(task);
    }

    public TaskDto updateTaskLabels(User caller, Integer taskId, Set<String> labels) {
        var task = findTask(caller, taskId);
        boardService.requireWritable(caller, task.getBoard());
        task.setLabels(labels);
        return saveAndAnnounce(task);
    }

    public Set<String> getAllLabels(User caller, Integer boardId) {
        return taskRepository.findDistinctLabels(boardService.resolve(caller, boardId));
    }

    public TaskDto assignParentTask(User caller, Integer childTaskId, Integer parentTaskId) {
        var childTask = findTask(caller, childTaskId);
        boardService.requireWritable(caller, childTask.getBoard());
        var parentTask = taskRepository.findById(parentTaskId)
                .orElseThrow(() -> parentNotFound(parentTaskId));

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
        boardService.requireWritable(caller, childTask.getBoard());
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

    private boolean canTaskBeCompleted(Task task) {
        return task.getParentTask() == null || task.getParentTask().isCompleted();
    }

    public TaskDto updateTaskCompletion(User caller, Integer taskId, boolean completed) {
        var task = findTask(caller, taskId);
        boardService.requireWritable(caller, task.getBoard());

        if (completed && !canTaskBeCompleted(task)) {
            throw new GlobalException(ExceptionIdentifier.PARENT_TASK_NOT_COMPLETED);
        }

        boolean changed = task.isCompleted() != completed;
        task.setCompleted(completed);
        if (!completed) {
            updateDependentTasksCompletion(task);
        }
        if (changed) {
            activityRecorder.completionChanged(caller, task, completed);
        }
        return saveAndAnnounce(task);
    }

    private void updateDependentTasksCompletion(Task parentTask) {
        updateDependentTasksCompletion(parentTask, new HashSet<>());
    }

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
        boardService.requireWritable(caller, task.getBoard());
        task.setDailyFocus(dailyFocus);
        return saveAndAnnounce(task);
    }

    @Scheduled(fixedRate = 1800000)
    public void checkAllTasksDeadlines() {
        var now = LocalDateTime.now();
        var claimed = taskRepository.claimTasksCrossingDeadline(now);
        if (claimed.isEmpty()) {
            return;
        }

        var newlyExpired = new ArrayList<Task>();
        for (Task task : taskRepository.findByIdIn(claimed)) {
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
