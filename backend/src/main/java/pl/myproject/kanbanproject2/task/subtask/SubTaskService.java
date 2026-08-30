package pl.myproject.kanbanproject2.task.subtask;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

@RequiredArgsConstructor
@Transactional
@Service
public class SubTaskService {

    private final SubTaskRepository subTaskRepository;
    private final TaskRepository taskRepository;
    private final SubTaskMapper subTaskMapper;
    private final BoardService boardService;

    public SubTaskDto addSubTask(User caller, CreateSubTaskRequest request) {
        var task = findTask(caller, request.task().id());

        var subTask = new SubTask();
        subTask.setTitle(request.title());
        subTask.setDescription(request.description());
        subTask.setCompleted(request.completed());
        subTask.setTask(task);
        subTask.setPosition(request.position() != null
                ? request.position()
                : nextPositionUnder(task));
        return subTaskMapper.toDto(subTaskRepository.save(subTask));
    }

    /**
     * The next free position among one task's subtasks. It used to be a count of every subtask in
     * the table, which both collided after a delete and numbered a task's first subtask by how
     * many other tasks happened to have some; then a fold over the fetched list, which was correct
     * but is the shape the task service has since moved away from. The aggregate belongs in the
     * database, where a null position simply does not take part in a MAX.
     *
     * <p>The task is never null here: {@code CreateSubTaskRequest} requires one, and a subtask
     * reads its board through it.
     */
    private int nextPositionUnder(Task task) {
        return subTaskRepository.findMaxPosition(task.getId()).orElse(0) + 1;
    }

    public List<SubTaskDto> getAllSubTasks(User caller, Integer boardId) {
        var board = boardService.resolve(caller, boardId);
        return subTaskRepository.findByTaskBoardOrderByIdAsc(board).stream()
                .map(subTaskMapper::toDto).toList();
    }

    public void deleteSubTask(User caller, Integer id) {
        subTaskRepository.delete(findSubTask(caller, id));
    }

    public SubTaskDto getSubTaskById(User caller, Integer id) {
        return subTaskMapper.toDto(findSubTask(caller, id));
    }

    public SubTaskDto patchSubTask(User caller, Integer id, PatchSubTaskRequest request) {
        var existingSubTask = findSubTask(caller, id);

        if (request.title().isPresent()) {
            var title = request.title().get();
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("A subtask title cannot be blank");
            }
            existingSubTask.setTitle(title);
        }
        if (request.description().isPresent()) {
            existingSubTask.setDescription(request.description().get());
        }
        if (request.completed().isPresent()) {
            var completed = request.completed().get();
            if (completed == null) {
                throw new IllegalArgumentException("A subtask completion state cannot be cleared");
            }
            existingSubTask.setCompleted(completed);
        }
        if (request.task().isPresent()) {
            var task = request.task().get();
            if (task == null) {
                // A subtask reads its board through its task, so clearing it would put the subtask
                // beyond every board at once - including the caller's own.
                throw new IllegalArgumentException("A subtask must belong to a task");
            }
            existingSubTask.setTask(findTask(caller, task.id()));
        }
        if (request.position().isPresent()) {
            var position = request.position().get();
            if (position == null) {
                throw new IllegalArgumentException("A subtask position cannot be cleared");
            }
            existingSubTask.setPosition(position);
        }

        return subTaskMapper.toDto(subTaskRepository.save(existingSubTask));
    }

    public SubTaskDto assignTaskToSubTask(User caller, Integer subTaskId, Integer taskId) {
        var subTask = findSubTask(caller, subTaskId);
        var task = findTask(caller, taskId);

        subTask.setTask(task);
        task.getSubTasks().add(subTask);

        taskRepository.save(task);
        return subTaskMapper.toDto(subTaskRepository.save(subTask));
    }

    public List<SubTaskDto> getSubTasksByTaskId(User caller, Integer taskId) {
        return findTask(caller, taskId).getSubTasks().stream().map(subTaskMapper::toDto).toList();
    }

    public SubTaskDto toggleSubTaskCompletion(User caller, Integer id) {
        var subTask = findSubTask(caller, id);
        subTask.setCompleted(!subTask.isCompleted());
        return subTaskMapper.toDto(subTaskRepository.save(subTask));
    }

    public SubTaskDto updateSubTaskPosition(User caller, Integer id, Integer position) {
        var subTask = findSubTask(caller, id);
        subTask.setPosition(position);
        return subTaskMapper.toDto(subTaskRepository.save(subTask));
    }

    /**
     * A subtask is visible exactly when the task it hangs off is. The null check is not defensive
     * padding: rows written before the task became mandatory can still have none, and the safe
     * reading of "belongs to no task" is "belongs to no board", not "belongs to every board".
     */
    private SubTask findSubTask(User caller, Integer id) {
        var subTask = subTaskRepository.findById(id).orElseThrow(() -> subTaskNotFound(id));
        if (subTask.getTask() == null || !subTask.getTask().getBoard().isVisibleTo(caller)) {
            throw subTaskNotFound(id);
        }
        return subTask;
    }

    private Task findTask(User caller, Integer id) {
        var task = taskRepository.findById(id).orElseThrow(() -> taskNotFound(id));
        if (!task.getBoard().isVisibleTo(caller)) {
            throw taskNotFound(id);
        }
        return task;
    }

    private GlobalException taskNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.TASK_NOT_FOUND,
                "Task not found with id: " + id);
    }

    private GlobalException subTaskNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.SUBTASK_NOT_FOUND,
                "Subtask not found with id: " + id);
    }
}
