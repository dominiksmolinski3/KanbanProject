package pl.myproject.kanbanproject2.task.subtask;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
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
    private final BoardEventPublisher boardEvents;

    public SubTaskDto addSubTask(User caller, CreateSubTaskRequest request) {
        var task = findTask(caller, request.task().id());
        boardService.requireWritable(caller, task.getBoard());

        var subTask = new SubTask();
        subTask.setTitle(request.title());
        subTask.setDescription(request.description());
        subTask.setCompleted(request.completed());
        subTask.setTask(task);
        subTask.setPosition(request.position() != null
                ? request.position()
                : nextPositionUnder(task));
        return saveAndAnnounce(subTask);
    }

    private int nextPositionUnder(Task task) {
        return subTaskRepository.findMaxPosition(task.getId()).orElse(0) + 1;
    }

    public List<SubTaskDto> getAllSubTasks(User caller, Integer boardId) {
        var board = boardService.resolve(caller, boardId);
        return subTaskRepository.findByTaskBoardOrderByIdAsc(board).stream()
                .map(subTaskMapper::toDto).toList();
    }

    public void deleteSubTask(User caller, Integer id) {
        var subTask = findSubTask(caller, id);
        boardService.requireWritable(caller, subTask.getTask().getBoard());
        subTaskRepository.delete(subTask);
        boardEvents.subtasksChanged(subTask.getTask().getBoard());
    }

    public SubTaskDto getSubTaskById(User caller, Integer id) {
        return subTaskMapper.toDto(findSubTask(caller, id));
    }

    public SubTaskDto patchSubTask(User caller, Integer id, PatchSubTaskRequest request) {
        var existingSubTask = findSubTask(caller, id);
        boardService.requireWritable(caller, existingSubTask.getTask().getBoard());

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
                throw new IllegalArgumentException("A subtask must belong to a task");
            }
            var target = findTask(caller, task.id());
            boardService.requireWritable(caller, target.getBoard());
            boardEvents.subtasksChanged(existingSubTask.getTask().getBoard());
            existingSubTask.setTask(target);
        }
        if (request.position().isPresent()) {
            var position = request.position().get();
            if (position == null) {
                throw new IllegalArgumentException("A subtask position cannot be cleared");
            }
            existingSubTask.setPosition(position);
        }

        return saveAndAnnounce(existingSubTask);
    }

    public SubTaskDto assignTaskToSubTask(User caller, Integer subTaskId, Integer taskId) {
        var subTask = findSubTask(caller, subTaskId);
        var task = findTask(caller, taskId);
        boardService.requireWritable(caller, subTask.getTask().getBoard());
        boardService.requireWritable(caller, task.getBoard());

        boardEvents.subtasksChanged(subTask.getTask().getBoard());
        subTask.setTask(task);
        task.getSubTasks().add(subTask);

        taskRepository.save(task);
        return saveAndAnnounce(subTask);
    }

    public List<SubTaskDto> getSubTasksByTaskId(User caller, Integer taskId) {
        return findTask(caller, taskId).getSubTasks().stream().map(subTaskMapper::toDto).toList();
    }

    public SubTaskDto toggleSubTaskCompletion(User caller, Integer id) {
        var subTask = findSubTask(caller, id);
        boardService.requireWritable(caller, subTask.getTask().getBoard());
        subTask.setCompleted(!subTask.isCompleted());
        return saveAndAnnounce(subTask);
    }

    public SubTaskDto updateSubTaskPosition(User caller, Integer id, Integer position) {
        var subTask = findSubTask(caller, id);
        boardService.requireWritable(caller, subTask.getTask().getBoard());
        subTask.setPosition(position);
        return saveAndAnnounce(subTask);
    }

    private SubTaskDto saveAndAnnounce(SubTask subTask) {
        var saved = subTaskRepository.save(subTask);
        boardEvents.subtasksChanged(saved.getTask().getBoard());
        return subTaskMapper.toDto(saved);
    }

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
