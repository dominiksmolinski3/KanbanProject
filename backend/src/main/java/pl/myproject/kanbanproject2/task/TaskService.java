package pl.myproject.kanbanproject2.task;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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

    public TaskDto addTask(CreateTaskRequest request) {
        var task = new Task();
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setPosition(request.position() != null
                ? request.position()
                : (int) taskRepository.count() + 1);
        task.setLabels(request.labels() != null ? new HashSet<>(request.labels()) : new HashSet<>());
        task.setColumn(request.column() != null ? findColumn(request.column().id()) : null);
        task.setRow(request.row() != null ? findRow(request.row().id()) : null);
        applyDeadline(task, request.deadline());

        var savedTask = taskRepository.save(task);

        if (savedTask.getColumn() != null) {
            saveTaskColumnHistory(savedTask, savedTask.getColumn());
        }
        return taskMapper.apply(savedTask);
    }

    public List<TaskDto> getAllTasks() {
        return taskRepository.findAll().stream()
                .map(taskMapper)
                .sorted(Comparator.comparing(TaskDto::position))
                .toList();
    }

    public void deleteTask(Integer id) {
        var task = findTask(id);

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

    public TaskDto getTaskById(Integer id) {
        return taskRepository.findById(id).map(taskMapper).orElseThrow(() -> taskNotFound(id));
    }

    public TaskDto patchTask(Integer id, PatchTaskRequest request) {
        var existingTask = findTask(id);

        if (request.title().isPresent()) {
            var title = request.title().get();
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("A task title cannot be blank");
            }
            existingTask.setTitle(title);
        }

        if (request.column().isPresent()) {
            var column = request.column().get();
            moveToColumn(existingTask, column == null ? null : findColumn(column.id()));
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
            existingTask.setRow(row == null ? null : findRow(row.id()));
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
     */
    private void moveToColumn(Task task, Column newColumn) {
        var currentColumn = task.getColumn();
        boolean unchanged = currentColumn == null
                ? newColumn == null
                : newColumn != null && currentColumn.getId().equals(newColumn.getId());
        if (unchanged) {
            return;
        }

        if (currentColumn != null) {
            saveTaskColumnHistory(task, currentColumn);
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

    public List<TaskColumnHistory> getTaskColumnHistory(Integer taskId) {
        var task = findTask(taskId);
        return taskColumnHistoryRepository.findByTaskOrderByChangedAtDesc(task);
    }

    public List<TaskColumnHistoryDto> getTaskColumnHistoryDTOs(Integer taskId) {
        return getTaskColumnHistory(taskId).stream().map(historyMapper::toDTO).toList();
    }

    public TaskDto assignUserToTask(Integer taskId, Integer userId) {
        if (!userService.checkWipStatus(userId)) {
            throw new GlobalException(ExceptionIdentifier.USER_WIP_LIMIT_EXCEEDED);
        }

        var task = findTask(taskId);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND,
                        "User not found with id: " + userId));

        task.getUsers().add(user);
        user.getTasks().add(task);

        userRepository.save(user);
        return taskMapper.apply(taskRepository.save(task));
    }

    public TaskDto removeUserFromTask(Integer taskId, Integer userId) {
        var task = findTask(taskId);
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND,
                        "User not found with id: " + userId));

        task.getUsers().remove(user);
        user.getTasks().remove(task);

        userRepository.save(user);
        return taskMapper.apply(taskRepository.save(task));
    }

    public TaskDto updateTaskPosition(Integer id, Integer position) {
        var task = findTask(id);
        task.setPosition(position);
        return taskMapper.apply(taskRepository.save(task));
    }

    public TaskDto addLabelToTask(Integer taskId, String label) {
        var task = findTask(taskId);
        if (task.getLabels() == null) {
            task.setLabels(new HashSet<>());
        }
        task.getLabels().add(label);
        return taskMapper.apply(taskRepository.save(task));
    }

    public TaskDto removeLabelFromTask(Integer taskId, String label) {
        var task = findTask(taskId);
        if (task.getLabels() != null) {
            task.getLabels().remove(label);
            return taskMapper.apply(taskRepository.save(task));
        }
        return taskMapper.apply(task);
    }

    public TaskDto updateTaskLabels(Integer taskId, Set<String> labels) {
        var task = findTask(taskId);
        task.setLabels(labels);
        return taskMapper.apply(taskRepository.save(task));
    }

    public Set<String> getAllLabels() {
        return taskRepository.findAll().stream()
                .filter(task -> task.getLabels() != null)
                .flatMap(task -> task.getLabels().stream())
                .collect(Collectors.toSet());
    }

    public TaskDto assignParentTask(Integer childTaskId, Integer parentTaskId) {
        var childTask = findTask(childTaskId);
        var parentTask = taskRepository.findById(parentTaskId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.PARENT_TASK_NOT_FOUND,
                        "Parent task not found with id: " + parentTaskId));

        if (wouldCreateCycle(childTask, parentTask)) {
            throw new GlobalException(ExceptionIdentifier.CYCLIC_TASK_DEPENDENCY);
        }

        childTask.setParentTask(parentTask);
        parentTask.getChildTasks().add(childTask);

        taskRepository.save(parentTask);
        return taskMapper.apply(taskRepository.save(childTask));
    }

    public TaskDto removeParentTask(Integer childTaskId) {
        var childTask = findTask(childTaskId);
        if (childTask.getParentTask() != null) {
            var parentTask = childTask.getParentTask();
            parentTask.getChildTasks().remove(childTask);
            childTask.setParentTask(null);
            taskRepository.save(parentTask);
        }
        return taskMapper.apply(taskRepository.save(childTask));
    }

    public List<TaskDto> getChildTasks(Integer taskId) {
        return findTask(taskId).getChildTasks().stream().map(taskMapper).toList();
    }

    public TaskDto getParentTask(Integer taskId) {
        var task = findTask(taskId);
        if (task.getParentTask() == null) {
            throw new GlobalException(ExceptionIdentifier.PARENT_TASK_NOT_SET);
        }
        return taskMapper.apply(task.getParentTask());
    }

    /**
     * Returns true if making {@code newParent} the parent of {@code child} would form a cycle,
     * i.e. {@code newParent} is already a descendant of {@code child}.
     */
    private boolean wouldCreateCycle(Task child, Task newParent) {
        if (child.getId().equals(newParent.getId())) {
            return true;
        }
        return child.getChildTasks().stream().anyMatch(c -> wouldCreateCycle(c, newParent));
    }

    public boolean canTaskBeCompleted(Integer taskId) {
        var task = findTask(taskId);
        return task.getParentTask() == null || task.getParentTask().isCompleted();
    }

    public TaskDto updateTaskCompletion(Integer taskId, boolean completed) {
        var task = findTask(taskId);

        if (completed && !canTaskBeCompleted(taskId)) {
            throw new GlobalException(ExceptionIdentifier.PARENT_TASK_NOT_COMPLETED);
        }

        task.setCompleted(completed);
        if (!completed) {
            updateDependentTasksCompletion(task);
        }
        return taskMapper.apply(taskRepository.save(task));
    }

    private void updateDependentTasksCompletion(Task parentTask) {
        parentTask.getChildTasks().forEach(childTask -> {
            if (childTask.isCompleted()) {
                childTask.setCompleted(false);
                taskRepository.save(childTask);
                updateDependentTasksCompletion(childTask);
            }
        });
    }

    public List<TaskDto> getDailyFocusTasks() {
        return taskRepository.findAllByDailyFocusTrue().stream().map(taskMapper).toList();
    }

    public TaskDto setDailyFocus(Integer taskId, boolean dailyFocus) {
        var task = findTask(taskId);
        if (task.isDailyFocus() == dailyFocus) {
            return taskMapper.apply(task);
        }
        task.setDailyFocus(dailyFocus);
        return taskMapper.apply(taskRepository.save(task));
    }

    @Scheduled(fixedRate = 1800000)
    public void checkAllTasksDeadlines() {
        var tasksWithDeadline = taskRepository.findAllByDeadlineIsNotNull();
        var now = LocalDateTime.now();

        for (Task task : tasksWithDeadline) {
            boolean wasExpired = task.isExpired();
            boolean isExpired = task.getDeadline().isBefore(now);
            if (wasExpired != isExpired) {
                task.setExpired(isExpired);
                taskRepository.save(task);
            }
        }
    }

    private Task findTask(Integer id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> taskNotFound(id));
    }

    private Column findColumn(Integer id) {
        return columnRepository.findById(id)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.COLUMN_NOT_FOUND,
                        "Column not found with id: " + id));
    }

    private Row findRow(Integer id) {
        return rowRepository.findById(id)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.ROW_NOT_FOUND,
                        "Row not found with id: " + id));
    }

    private GlobalException taskNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.TASK_NOT_FOUND,
                "Task not found with id: " + id);
    }
}
