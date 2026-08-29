package pl.myproject.kanbanproject2.task.subtask;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;

import java.util.List;

@RequiredArgsConstructor
@Transactional
@Service
public class SubTaskService {

    private final SubTaskRepository subTaskRepository;
    private final TaskRepository taskRepository;
    private final SubTaskMapper subTaskMapper;

    public SubTaskDto addSubTask(CreateSubTaskRequest request) {
        var subTask = new SubTask();
        subTask.setTitle(request.title());
        subTask.setDescription(request.description());
        subTask.setCompleted(request.completed());
        subTask.setPosition(request.position() != null
                ? request.position()
                : (int) subTaskRepository.count() + 1);
        subTask.setTask(request.task() != null ? findTask(request.task().id()) : null);
        return subTaskMapper.toDto(subTaskRepository.save(subTask));
    }

    public List<SubTaskDto> getAllSubTasks() {
        return subTaskRepository.findAll().stream().map(subTaskMapper::toDto).toList();
    }

    public void deleteSubTask(Integer id) {
        if (!subTaskRepository.existsById(id)) {
            throw subTaskNotFound(id);
        }
        subTaskRepository.deleteById(id);
    }

    public SubTaskDto getSubTaskById(Integer id) {
        return subTaskRepository.findById(id).map(subTaskMapper::toDto)
                .orElseThrow(() -> subTaskNotFound(id));
    }

    public SubTaskDto patchSubTask(Integer id, PatchSubTaskRequest request) {
        var existingSubTask = findSubTask(id);

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
            existingSubTask.setTask(task == null ? null : findTask(task.id()));
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

    public SubTaskDto assignTaskToSubTask(Integer subTaskId, Integer taskId) {
        var subTask = findSubTask(subTaskId);
        var task = findTask(taskId);

        subTask.setTask(task);
        task.getSubTasks().add(subTask);

        taskRepository.save(task);
        return subTaskMapper.toDto(subTaskRepository.save(subTask));
    }

    public List<SubTaskDto> getSubTasksByTaskId(Integer taskId) {
        return findTask(taskId).getSubTasks().stream().map(subTaskMapper::toDto).toList();
    }

    public SubTaskDto toggleSubTaskCompletion(Integer id) {
        var subTask = findSubTask(id);
        subTask.setCompleted(!subTask.isCompleted());
        return subTaskMapper.toDto(subTaskRepository.save(subTask));
    }

    public SubTaskDto updateSubTaskPosition(Integer id, Integer position) {
        var subTask = findSubTask(id);
        subTask.setPosition(position);
        return subTaskMapper.toDto(subTaskRepository.save(subTask));
    }

    private SubTask findSubTask(Integer id) {
        return subTaskRepository.findById(id).orElseThrow(() -> subTaskNotFound(id));
    }

    private Task findTask(Integer id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.TASK_NOT_FOUND,
                        "Task not found with id: " + id));
    }

    private GlobalException subTaskNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.SUBTASK_NOT_FOUND,
                "Subtask not found with id: " + id);
    }
}
