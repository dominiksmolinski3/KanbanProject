package pl.myproject.kanbanproject2.task.subtask;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code task/subtask} had no test at all, which held the per-package JaCoCo floor at zero. The
 * two behaviours worth pinning are position scoping — the next position comes from the parent
 * task's own subtasks, not a count of the whole table — and the tri-state patch, where "absent"
 * and "explicitly null" must stay distinguishable or a body of {@code {"description": ...}}
 * silently un-ticks the subtask.
 */
class SubTaskServiceTest {

    private SubTaskRepository subTaskRepository;
    private TaskRepository taskRepository;
    private SubTaskService service;
    private pl.myproject.kanbanproject2.board.Board board;
    private pl.myproject.kanbanproject2.user.User caller;
    private pl.myproject.kanbanproject2.board.BoardService boardService;
    private BoardEventPublisher boardEvents;

    @BeforeEach
    void setUp() {
        subTaskRepository = mock(SubTaskRepository.class);
        taskRepository = mock(TaskRepository.class);
        var tenant = pl.myproject.kanbanproject2.board.TenancyFixtures.tenant();
        board = tenant.board();
        caller = tenant.caller();
        boardService = tenant.boardService();
        boardEvents = mock(BoardEventPublisher.class);
        service = new SubTaskService(subTaskRepository, taskRepository, new SubTaskMapper(),
                boardService, boardEvents);

        when(subTaskRepository.save(any(SubTask.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Task task(Integer id) {
        var task = new Task();
        task.setId(id);
        task.setSubTasks(new ArrayList<>());
        task.setBoard(board);
        return task;
    }

    /** Every subtask hangs off a task on the board, because that is where its board comes from. */
    private SubTask subTask(Integer id, String title, Integer position) {
        var subTask = new SubTask();
        subTask.setId(id);
        subTask.setTitle(title);
        subTask.setPosition(position);
        subTask.setTask(task(7));
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
            when(subTaskRepository.findMaxPosition(7)).thenReturn(Optional.of(4));

            var created = service.addSubTask(caller,
                    new CreateSubTaskRequest("c", null, false, null, new IdRef(7)));

            assertThat(created.position()).isEqualTo(5);
        }

        @Test
        @DisplayName("a gap left by a delete does not collide - the max is used, not the count")
        void positionUsesTheMaxRatherThanTheCount() {
            var parent = task(7);
            when(taskRepository.findById(7)).thenReturn(Optional.of(parent));
            when(subTaskRepository.findMaxPosition(7)).thenReturn(Optional.of(3));

            var created = service.addSubTask(caller,
                    new CreateSubTaskRequest("d", null, false, null, new IdRef(7)));

            assertThat(created.position()).isEqualTo(4);
        }

        @Test
        @DisplayName("the first subtask of an empty task is numbered 1, not by the table size")
        void firstSubtaskStartsAtOne() {
            var parent = task(7);
            when(taskRepository.findById(7)).thenReturn(Optional.of(parent));
            when(subTaskRepository.findMaxPosition(7)).thenReturn(Optional.empty());

            var created = service.addSubTask(caller,
                    new CreateSubTaskRequest("first", null, false, null, new IdRef(7)));

            assertThat(created.position()).isEqualTo(1);
            verify(subTaskRepository, never()).count();
        }

        @Test
        @DisplayName("an explicit position is kept and the parent's subtasks are not read")
        void explicitPositionWins() {
            var parent = task(7);
            when(taskRepository.findById(7)).thenReturn(Optional.of(parent));

            var created = service.addSubTask(caller,
                    new CreateSubTaskRequest("c", "why", true, 42, new IdRef(7)));

            assertThat(created.position()).isEqualTo(42);
            assertThat(created.completed()).isTrue();
            assertThat(created.description()).isEqualTo("why");
            assertThat(created.taskId()).isEqualTo(7);
            verify(subTaskRepository, never()).findMaxPosition(any());
        }

        @Test
        @DisplayName("a subtask on another board is a 404, not somebody else's checklist item")
        void subtasksOnAnotherBoardAreNotFound() {
            var elsewhere = new Task();
            elsewhere.setId(99);
            elsewhere.setBoard(pl.myproject.kanbanproject2.board.TenancyFixtures.board(
                    404, pl.myproject.kanbanproject2.board.TenancyFixtures.user(2)));
            var theirs = subTask(5, "not yours", 1);
            theirs.setTask(elsewhere);
            when(subTaskRepository.findById(5)).thenReturn(Optional.of(theirs));

            assertThatThrownBy(() -> service.getSubTaskById(caller, 5))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.SUBTASK_NOT_FOUND);
        }

        @Test
        @DisplayName("an unknown parent task is a 404 rather than a flush-time constraint violation")
        void unknownParentTaskIs404() {
            when(taskRepository.findById(404)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addSubTask(caller,
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

            var patched = service.patchSubTask(caller, 1,
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

            var patched = service.patchSubTask(caller, 1,
                    patch(null, JsonNullable.of(null), null, null, null));

            assertThat(patched.description()).isNull();
        }

        @Test
        @DisplayName("a blank title is refused instead of being written")
        void blankTitleIsRefused() {
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "title", 1)));

            assertThatThrownBy(() -> service.patchSubTask(caller, 1,
                    patch(JsonNullable.of("   "), null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");

            verify(subTaskRepository, never()).save(any());
        }

        @Test
        @DisplayName("an explicitly null title is refused - it is not a clearable field")
        void nullTitleIsRefused() {
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "title", 1)));

            assertThatThrownBy(() -> service.patchSubTask(caller, 1,
                    patch(JsonNullable.of(null), null, null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("completion and position cannot be cleared, only set")
        void completionAndPositionCannotBeCleared() {
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "title", 1)));

            assertThatThrownBy(() -> service.patchSubTask(caller, 1,
                    patch(null, null, JsonNullable.of(null), null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("completion");

            assertThatThrownBy(() -> service.patchSubTask(caller, 1,
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

            var patched = service.patchSubTask(caller, 1, patch(
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
        @DisplayName("an explicitly null task is refused - a subtask reads its board through its task")
        void nullTaskIsRefused() {
            var existing = subTask(1, "title", 1);
            existing.setTask(task(7));
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(existing));

            // Detaching used to be allowed and produced a subtask on no board at all: invisible to
            // its own author and to everyone else, with no route that could reattach it.
            assertThatThrownBy(() -> service.patchSubTask(caller, 1,
                    patch(null, null, null, null, JsonNullable.of(null))))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(existing.getTask()).isNotNull();
        }

        @Test
        @DisplayName("an empty patch saves the subtask unchanged rather than failing")
        void emptyPatchIsANoOp() {
            var existing = subTask(1, "title", 3);
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(existing));

            var patched = service.patchSubTask(caller, 1, patchNothing());

            assertThat(patched.title()).isEqualTo("title");
            assertThat(patched.position()).isEqualTo(3);
        }

        @Test
        @DisplayName("patching a subtask that does not exist is a 404")
        void unknownSubtaskIs404() {
            when(subTaskRepository.findById(404)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.patchSubTask(caller, 404, patchNothing()))
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
            when(subTaskRepository.findByTaskBoardOrderByIdAsc(board))
                    .thenReturn(List.of(subTask(1, "a", 1), subTask(2, "b", 2)));

            assertThat(service.getAllSubTasks(caller, null))
                    .extracting(SubTaskDto::title)
                    .containsExactly("a", "b");
        }

        @Test
        @DisplayName("reading one subtask by id maps it, and a missing one is a 404")
        void readOne() {
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "a", 1)));
            when(subTaskRepository.findById(404)).thenReturn(Optional.empty());

            assertThat(service.getSubTaskById(caller, 1).title()).isEqualTo("a");
            assertThatThrownBy(() -> service.getSubTaskById(caller, 404))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.SUBTASK_NOT_FOUND);
        }

        @Test
        @DisplayName("deleting reads the subtask first, so a missing id is a 404 not a silent no-op")
        void deleteChecksExistence() {
            var existing = subTask(1, "a", 1);
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(existing));
            service.deleteSubTask(caller, 1);
            verify(subTaskRepository).delete(existing);

            when(subTaskRepository.findById(404)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteSubTask(caller, 404))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.SUBTASK_NOT_FOUND);
            // Reading it first is what makes the board check possible at all - existsById cannot
            // tell you whose board the subtask is on.
            verify(subTaskRepository, never()).delete(null);
        }

        @Test
        @DisplayName("the subtasks of a task are read off the task, and an unknown task is a 404")
        void subtasksByTaskId() {
            var parent = task(7);
            parent.getSubTasks().add(subTask(1, "a", 1));
            when(taskRepository.findById(7)).thenReturn(Optional.of(parent));
            when(taskRepository.findById(404)).thenReturn(Optional.empty());

            assertThat(service.getSubTasksByTaskId(caller, 7))
                    .extracting(SubTaskDto::title).containsExactly("a");
            assertThatThrownBy(() -> service.getSubTasksByTaskId(caller, 404))
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

            var assigned = service.assignTaskToSubTask(caller, 1, 7);

            assertThat(assigned.taskId()).isEqualTo(7);
            assertThat(parent.getSubTasks()).containsExactly(existing);
            verify(taskRepository).save(parent);
        }

        @Test
        @DisplayName("toggling flips completion in both directions")
        void toggleFlips() {
            var existing = subTask(1, "a", 1);
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(existing));

            assertThat(service.toggleSubTaskCompletion(caller, 1).completed()).isTrue();
            assertThat(service.toggleSubTaskCompletion(caller, 1).completed()).isFalse();
        }

        @Test
        @DisplayName("the position endpoint writes the position it is given")
        void updatePosition() {
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "a", 1)));

            assertThat(service.updateSubTaskPosition(caller, 1, 12).position()).isEqualTo(12);
        }

        @Test
        @DisplayName("the mapper answers null for a null entity rather than throwing")
        void mapperToleratesNull() {
            assertThat(new SubTaskMapper().toDto(null)).isNull();
        }
    }

    /**
     * FEAT-08 left this service out: a viewer could see a card and still add, tick, reorder, move
     * or delete its subtasks, because every lookup here checked visibility and nothing checked the
     * role. Each write now asks {@code requireWritable}; the reads still do not.
     */
    @Nested
    @DisplayName("a viewer")
    class Viewer {

        private final GlobalException readOnly = new GlobalException(ExceptionIdentifier.VIEWER_READ_ONLY);

        @BeforeEach
        void refuseWrites() {
            doThrow(readOnly).when(boardService).requireWritable(any(), any(pl.myproject.kanbanproject2.board.Board.class));
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "a", 1)));
            when(taskRepository.findById(7)).thenReturn(Optional.of(task(7)));
        }

        @Test
        @DisplayName("cannot add a subtask")
        void cannotAdd() {
            var request = new CreateSubTaskRequest("t", null, false, null, new IdRef(7));

            assertThatThrownBy(() -> service.addSubTask(caller, request)).isSameAs(readOnly);
            verify(subTaskRepository, never()).save(any());
        }

        @Test
        @DisplayName("cannot patch, tick, reposition, reassign or delete one")
        void cannotChangeOne() {
            var rename = patch(JsonNullable.of("b"), null, null, null, null);

            assertThatThrownBy(() -> service.patchSubTask(caller, 1, rename)).isSameAs(readOnly);
            assertThatThrownBy(() -> service.toggleSubTaskCompletion(caller, 1)).isSameAs(readOnly);
            assertThatThrownBy(() -> service.updateSubTaskPosition(caller, 1, 3)).isSameAs(readOnly);
            assertThatThrownBy(() -> service.assignTaskToSubTask(caller, 1, 7)).isSameAs(readOnly);
            assertThatThrownBy(() -> service.deleteSubTask(caller, 1)).isSameAs(readOnly);

            verify(subTaskRepository, never()).save(any());
            verify(subTaskRepository, never()).delete(any());
            verifyNoInteractions(boardEvents);
        }

        @Test
        @DisplayName("can still read them")
        void canRead() {
            assertThat(service.getSubTaskById(caller, 1).title()).isEqualTo("a");
            assertThat(service.getSubTasksByTaskId(caller, 7)).isEmpty();
        }
    }

    /**
     * SYNC-01: a card carries its open-subtask count, so every subtask write has to tell the board's
     * other viewers or their "unfinished subtasks" warning sits wrong until a reload. The publisher
     * holds the frame until the commit, so announcing inside the service is not announcing early.
     */
    @Nested
    @DisplayName("announcing")
    class Announcing {

        @Test
        @DisplayName("adding, ticking, editing and repositioning each announce the board")
        void writesAnnounce() {
            var existing = subTask(1, "a", 1);
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(existing));
            when(taskRepository.findById(7)).thenReturn(Optional.of(task(7)));

            service.addSubTask(caller, new CreateSubTaskRequest("t", null, false, 1, new IdRef(7)));
            service.toggleSubTaskCompletion(caller, 1);
            service.patchSubTask(caller, 1, patch(JsonNullable.of("b"), null, null, null, null));
            service.updateSubTaskPosition(caller, 1, 4);

            verify(boardEvents, times(4)).subtasksChanged(board);
        }

        @Test
        @DisplayName("a delete announces too, though it maps nothing")
        void deleteAnnounces() {
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "a", 1)));

            service.deleteSubTask(caller, 1);

            verify(boardEvents).subtasksChanged(board);
        }

        @Test
        @DisplayName("moving a subtask to another board's card announces both boards")
        void moveAnnouncesBothBoards() {
            var elsewhere = pl.myproject.kanbanproject2.board.TenancyFixtures.board(2, caller);
            var target = task(8);
            target.setBoard(elsewhere);
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "a", 1)));
            when(taskRepository.findById(8)).thenReturn(Optional.of(target));

            service.patchSubTask(caller, 1, patch(null, null, null, null, JsonNullable.of(new IdRef(8))));

            verify(boardEvents).subtasksChanged(board);
            verify(boardEvents).subtasksChanged(elsewhere);
        }

        @Test
        @DisplayName("a read announces nothing")
        void readsAreSilent() {
            when(subTaskRepository.findById(1)).thenReturn(Optional.of(subTask(1, "a", 1)));
            when(taskRepository.findById(7)).thenReturn(Optional.of(task(7)));

            service.getSubTaskById(caller, 1);
            service.getSubTasksByTaskId(caller, 7);

            verifyNoInteractions(boardEvents);
        }
    }
}
