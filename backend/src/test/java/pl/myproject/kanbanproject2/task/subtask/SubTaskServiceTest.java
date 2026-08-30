package pl.myproject.kanbanproject2.task.subtask;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.IdRef;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code task/subtask} had no test that reached it at all, which is what held the per-package
 * JaCoCo floor at zero. The two behaviours worth pinning rather than merely executing are the
 * position scoping - the next position comes from the parent task's own subtasks, not from a count
 * of the whole table - and the tri-state patch, where "absent" and "explicitly null" have to stay
 * distinguishable or a body of {@code {"description": ...}} silently un-ticks the subtask.
 */
class SubTaskServiceTest {

    private SubTaskRepository subTaskRepository;
    private TaskRepository taskRepository;
    private SubTaskService service;

    @BeforeEach
    void setUp() {
        subTaskRepository = mock(SubTaskRepository.class);
        taskRepository = mock(TaskRepository.class);
        service = new SubTaskService(subTaskRepository, taskRepository, new SubTaskMapper());

        when(subTaskRepository.save(any(SubTask.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static Task task(Integer id) {
        var task = new Task();
        task.setId(id);
        task.setSubTasks(new ArrayList<>());
        return task;
    }

    private static SubTask subTask(Integer id, String title, Integer position) {
        var subTask = new SubTask();
        subTask.setId(id);
        subTask.setTitle(title);
        subTask.setPosition(position);
        return subTask;
    }

    private static PatchSubTaskRequest patch(JsonNullable<String> title,
                                             JsonNullable<String> description,
                                             JsonNullable<Boolean> completed,
                                             JsonNullable<Integer> position,
                                             JsonNullable<IdRef> task) {
        return new PatchSubTaskRequest(title, description, completed, position, task);
    }

    private static PatchSubTaskRequest patchNothing() {
        return patch(null, null, null, null, null);
    }

    @Nested
    @DisplayName("creating")
    class Creating {

        @Test
        @DisplayName("the next position is the highest under the same parent task, plus one")
        void positionIsScopedToTheParentTask() {
            var parent = task(7);
            when(taskRepository.findById(7)).thenReturn(Optional.of(parent));
            when(subTaskRepository.findByTask(parent))
                    .thenReturn(List.of(subTask(1, "a", 1), subTask(2, "b", 4)));

            var created = service.addSubTask(
                    new CreateSubTaskRequest("c", null, false, null, new IdRef(7)));

            assertThat(created.position()).isEqualTo(5);
        }

        @Test
        @DisplayName("a gap left by a delete does not collide - the max is used, not the count")
        void positionUsesTheMaxRatherThanTheCount() {
            var parent = task(7);
            when(taskRepository.findById(7)).thenReturn(Optional.of(parent));
            when(subTaskRepository.findByTask(parent))
                    .thenReturn(List.of(subTask(1, "a", 1), subTask(3, "c", 3)));

            var created = service.addSubTask(
                    new CreateSubTaskRequest("d", null, false, null, new IdRef(7)));

            assertThat(created.position()).isEqualTo(4);
        }

        @Test
        @DisplayName("a subtask already carrying a null position is skipped rather than throwing")
        void nullPositionsAreTolerated() {
            var parent = task(7);
            when(taskRepository.findById(7)).thenReturn(Optional.of(parent));
            when(subTaskRepository.findByTask(parent))
                    .thenReturn(List.of(subTask(1, "a", null), subTask(2, "b", 2)));

            var created = service.addSubTask(
                    new CreateSubTaskRequest("c", null, false, null, new IdRef(7)));

            assertThat(created.position()).isEqualTo(3);
        }

        @Test
        @DisplayName("the first subtask of an empty task is numbered 1, not by the table size")
        void firstSubtaskStartsAtOne() {
            var parent = task(7);
            when(taskRepository.findById(7)).thenReturn(Optional.of(parent));
            when(subTaskRepository.findByTask(parent)).thenReturn(List.of());

            var created = service.addSubTask(
                    new CreateSubTaskRequest("first", null, false, null, new IdRef(7)));

            assertThat(created.position()).isEqualTo(1);
            verify(subTaskRepository, never()).count();
        }

        @Test
        @DisplayName("an explicit position is kept and the parent's subtasks are not read")
        void explicitPositionWins() {
            var parent = task(7);
            when(taskRepository.findById(7)).thenReturn(Optional.of(parent));

            var created = service.addSubTask(
                    new CreateSubTaskRequest("c", "why", true, 42, new IdRef(7)));

            assertThat(created.position()).isEqualTo(42);
            assertThat(created.completed()).isTrue();
            assertThat(created.description()).isEqualTo("why");
            assertThat(created.taskId()).isEqualTo(7);
            verify(subTaskRepository, never()).findByTask(any());
        }

        @Test
        @DisplayName("an orphan subtask is numbered among the other orphans")
        void orphansAreTheirOwnScope() {
            when(subTaskRepository.findByTask(null)).thenReturn(List.of(subTask(9, "loose", 2)));

            var created = service.addSubTask(
                    new CreateSubTaskRequest("also loose", null, false, null, null));

            assertThat(created.position()).isEqualTo(3);
            assertThat(created.taskId()).isNull();
            verify(taskRepository, never()).findById(any());
        }

        @Test
        @DisplayName("an unknown parent task is a 404 rather than a flush-time constraint violation")
        void unknownParentTaskIs404() {
            when(taskRepository.findById(404)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addSubTask(
                    new CreateSubTaskRequest("c", null, false, null, new IdRef(404))))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.TASK_NOT_FOUND);

            verify(subTaskRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("patching")
    class Patching {

        @Test
        @DisplayName("a body that never mentions completed leaves it alone")
        void absentCompletedIsNotACleardown() {
            var existing = subTask(1, "title", 1);
            existing.setCompleted(true);
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(existing));

            var patched = service.patchSubTask(1,
                    patch(null, JsonNullable.of("new description"), null, null, null));

            assertThat(patched.completed()).isTrue();
            assertThat(patched.description()).isEqualTo("new description");
            assertThat(patched.title()).isEqualTo("title");
        }

        @Test
        @DisplayName("an explicitly null description clears it")
        void explicitNullDescriptionClears() {
            var existing = subTask(1, "title", 1);
            existing.setDescription("old");
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(existing));

            var patched = service.patchSubTask(1,
                    patch(null, JsonNullable.of(null), null, null, null));

            assertThat(patched.description()).isNull();
        }

        @Test
        @DisplayName("a blank title is refused instead of being written")
        void blankTitleIsRefused() {
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "title", 1)));

            assertThatThrownBy(() -> service.patchSubTask(1,
                    patch(JsonNullable.of("   "), null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");

            verify(subTaskRepository, never()).save(any());
        }

        @Test
        @DisplayName("an explicitly null title is refused - it is not a clearable field")
        void nullTitleIsRefused() {
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "title", 1)));

            assertThatThrownBy(() -> service.patchSubTask(1,
                    patch(JsonNullable.of(null), null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("completion and position cannot be cleared, only set")
        void completionAndPositionCannotBeCleared() {
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "title", 1)));

            assertThatThrownBy(() -> service.patchSubTask(1,
                    patch(null, null, JsonNullable.of(null), null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("completion");

            assertThatThrownBy(() -> service.patchSubTask(1,
                    patch(null, null, null, JsonNullable.of(null), null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("position");
        }

        @Test
        @DisplayName("every writable field moves when the body names all of them")
        void everyFieldIsWritable() {
            var existing = subTask(1, "title", 1);
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(existing));
            when(taskRepository.findById(7)).thenReturn(Optional.of(task(7)));

            var patched = service.patchSubTask(1, patch(
                    JsonNullable.of("renamed"),
                    JsonNullable.of("described"),
                    JsonNullable.of(true),
                    JsonNullable.of(9),
                    JsonNullable.of(new IdRef(7))));

            assertThat(patched.title()).isEqualTo("renamed");
            assertThat(patched.description()).isEqualTo("described");
            assertThat(patched.completed()).isTrue();
            assertThat(patched.position()).isEqualTo(9);
            assertThat(patched.taskId()).isEqualTo(7);
        }

        @Test
        @DisplayName("an explicitly null task detaches the subtask from its parent")
        void nullTaskDetaches() {
            var existing = subTask(1, "title", 1);
            existing.setTask(task(7));
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(existing));

            var patched = service.patchSubTask(1,
                    patch(null, null, null, null, JsonNullable.of(null)));

            assertThat(patched.taskId()).isNull();
        }

        @Test
        @DisplayName("an empty patch saves the subtask unchanged rather than failing")
        void emptyPatchIsANoOp() {
            var existing = subTask(1, "title", 3);
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(existing));

            var patched = service.patchSubTask(1, patchNothing());

            assertThat(patched.title()).isEqualTo("title");
            assertThat(patched.position()).isEqualTo(3);
        }

        @Test
        @DisplayName("patching a subtask that does not exist is a 404")
        void unknownSubtaskIs404() {
            when(subTaskRepository.findById(404)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.patchSubTask(404, patchNothing()))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.SUBTASK_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("reading and deleting")
    class ReadingAndDeleting {

        @Test
        @DisplayName("the listing maps every subtask through the mapper")
        void listMapsEverything() {
            when(subTaskRepository.findAll())
                    .thenReturn(List.of(subTask(1, "a", 1), subTask(2, "b", 2)));

            assertThat(service.getAllSubTasks())
                    .extracting(SubTaskDto::title)
                    .containsExactly("a", "b");
        }

        @Test
        @DisplayName("reading one subtask by id maps it, and a missing one is a 404")
        void readOne() {
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "a", 1)));
            when(subTaskRepository.findById(404)).thenReturn(Optional.empty());

            assertThat(service.getSubTaskById(1).title()).isEqualTo("a");
            assertThatThrownBy(() -> service.getSubTaskById(404))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.SUBTASK_NOT_FOUND);
        }

        @Test
        @DisplayName("deleting checks existence first, so a missing id is a 404 not a silent no-op")
        void deleteChecksExistence() {
            when(subTaskRepository.existsById(1)).thenReturn(true);
            service.deleteSubTask(1);
            verify(subTaskRepository).deleteById(1);

            when(subTaskRepository.existsById(404)).thenReturn(false);
            assertThatThrownBy(() -> service.deleteSubTask(404))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.SUBTASK_NOT_FOUND);
            verify(subTaskRepository, never()).deleteById(404);
        }

        @Test
        @DisplayName("the subtasks of a task are read off the task, and an unknown task is a 404")
        void subtasksByTaskId() {
            var parent = task(7);
            parent.getSubTasks().add(subTask(1, "a", 1));
            when(taskRepository.findById(7)).thenReturn(Optional.of(parent));
            when(taskRepository.findById(404)).thenReturn(Optional.empty());

            assertThat(service.getSubTasksByTaskId(7))
                    .extracting(SubTaskDto::title).containsExactly("a");
            assertThatThrownBy(() -> service.getSubTasksByTaskId(404))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.TASK_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("assignment and toggles")
    class AssignmentAndToggles {

        @Test
        @DisplayName("assigning writes both sides of the relation")
        void assignWritesBothSides() {
            var parent = task(7);
            var existing = subTask(1, "a", 1);
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(existing));
            when(taskRepository.findById(7)).thenReturn(Optional.of(parent));

            var assigned = service.assignTaskToSubTask(1, 7);

            assertThat(assigned.taskId()).isEqualTo(7);
            assertThat(parent.getSubTasks()).containsExactly(existing);
            verify(taskRepository).save(parent);
        }

        @Test
        @DisplayName("toggling flips completion in both directions")
        void toggleFlips() {
            var existing = subTask(1, "a", 1);
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(existing));

            assertThat(service.toggleSubTaskCompletion(1).completed()).isTrue();
            assertThat(service.toggleSubTaskCompletion(1).completed()).isFalse();
        }

        @Test
        @DisplayName("the position endpoint writes the position it is given")
        void updatePosition() {
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "a", 1)));

            assertThat(service.updateSubTaskPosition(1, 12).position()).isEqualTo(12);
        }

        @Test
        @DisplayName("the mapper answers null for a null entity rather than throwing")
        void mapperToleratesNull() {
            assertThat(new SubTaskMapper().toDto(null)).isNull();
        }
    }
}
