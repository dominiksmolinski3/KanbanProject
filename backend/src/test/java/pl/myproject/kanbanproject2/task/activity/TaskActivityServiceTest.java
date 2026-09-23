package pl.myproject.kanbanproject2.task.activity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskActivityServiceTest {
    private TaskActivityRepository repository;
    private BoardService boardService;
    private TaskActivityService service;
    private TaskActivityRecorder recorder;

    private User caller;
    private Board board;
    private Task task;

    @BeforeEach
    void setUp() {
        repository = mock(TaskActivityRepository.class);
        boardService = mock(BoardService.class);
        service = new TaskActivityService(repository, new TaskActivityMapper(), boardService);
        recorder = new TaskActivityRecorder(repository);

        caller = TenancyFixtures.user(1);
        caller.setName("Ada");
        board = TenancyFixtures.board(10, caller);

        task = new Task();
        task.setId(5);
        task.setTitle("Ship the release");
        task.setBoard(board);

        when(boardService.resolve(any(), any())).thenReturn(board);
        when(repository.save(any(TaskActivity.class))).thenAnswer(call -> call.getArgument(0));
        when(repository.findByBoardOrderByOccurredAtDescIdDesc(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
    }

    private TaskActivity captureSaved() {
        var saved = ArgumentCaptor.forClass(TaskActivity.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    @Nested
    @DisplayName("recording")
    class Recording {

        @Test
        @DisplayName("an entry names the task, the actor and what happened")
        void anEntryIsSelfContained() {
            recorder.created(caller, task);

            var entry = captureSaved();
            assertThat(entry.getType()).isEqualTo(TaskActivityType.CREATED);
            assertThat(entry.getTaskTitle()).isEqualTo("Ship the release");
            assertThat(entry.getActorName()).isEqualTo("Ada");
            assertThat(entry.getBoard()).isSameAs(board);
            assertThat(entry.getOccurredAt()).isNotNull();
        }

        @Test
        @DisplayName("the title and the actor's name are copied, so a later rename does not rewrite history")
        void namesAreCopiedNotResolved() {
            recorder.created(caller, task);
            var entry = captureSaved();

            task.setTitle("Something else entirely");
            caller.setName("Somebody else");

            assertThat(entry.getTaskTitle()).isEqualTo("Ship the release");
            assertThat(entry.getActorName()).isEqualTo("Ada");
        }

        @Test
        @DisplayName("a move carries the column name, an assignment carries the person")
        void detailCarriesTheObject() {
            recorder.moved(caller, task, "In Progress");
            assertThat(captureSaved().getDetail()).isEqualTo("In Progress");
        }

        @ParameterizedTest(name = "completed={0} is {1}")
        @CsvSource({"true,COMPLETED", "false,REOPENED"})
        @DisplayName("completion is two entries, not one with a flag")
        void completionIsTwoTypes(boolean completed, TaskActivityType expected) {
            recorder.completionChanged(caller, task, completed);
            assertThat(captureSaved().getType()).isEqualTo(expected);
        }

        @Test
        @DisplayName("an untitled task still produces a readable entry rather than a null column")
        void anUntitledTaskIsStillRecorded() {
            task.setTitle("  ");
            recorder.created(caller, task);

            assertThat(captureSaved().getTaskTitle()).isEmpty();
        }

        @Test
        @DisplayName("a task with no board records nothing rather than throwing into somebody else's edit")
        void aBoardlessTaskIsIgnored() {
            var orphan = new Task();
            orphan.setId(6);

            recorder.created(caller, orphan);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("deleting detaches the entries instead of deleting them")
        void deletingDetaches() {
            var entry = new TaskActivity(board, task, caller, TaskActivityType.CREATED, null);
            when(repository.findByTask(task)).thenReturn(List.of(entry));

            recorder.detachFrom(task);

            assertThat(entry.getTask()).isNull();
            assertThat(entry.getTaskTitle()).isEqualTo("Ship the release");
            verify(repository).saveAll(List.of(entry));
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("the default page is 25, and the caller is asked for by the board service")
        void defaultsToTwentyFive() {
            service.feed(caller, null, null, null);

            verify(repository).findByBoardOrderByOccurredAtDescIdDesc(
                    eq(board), eq(PageRequest.of(0, TaskActivityService.DEFAULT_PAGE_SIZE)));
            verify(boardService).resolve(caller, null);
        }

        @Test
        @DisplayName("the totals come back, so a short page can be told from the last one")
        void totalsAreReported() {
            var entry = new TaskActivity(board, task, caller, TaskActivityType.MOVED, "Done");
            when(repository.findByBoardOrderByOccurredAtDescIdDesc(any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 25), 60));

            var results = service.feed(caller, 10, 0, 25);

            assertThat(results.activities()).hasSize(1);
            assertThat(results.totalEntries()).isEqualTo(60);
            assertThat(results.totalPages()).isEqualTo(3);
            assertThat(results.activities().getFirst().detail()).isEqualTo("Done");
        }

        @ParameterizedTest(name = "page={0} size={1}")
        @CsvSource({"-1,25", "0,0", "0,101", "0,-5"})
        @DisplayName("a page this route will not serve is refused rather than quietly clamped")
        void anUnservablePageIsRefused(int page, int size) {
            assertThatThrownBy(() -> service.feed(caller, 10, page, size))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_ACTIVITY_REQUEST);
        }

        @Test
        @DisplayName("the maximum is served rather than refused - it is the boundary, not past it")
        void theMaximumItselfIsFine() {
            service.feed(caller, 10, 0, TaskActivityService.MAX_PAGE_SIZE);

            verify(repository).findByBoardOrderByOccurredAtDescIdDesc(
                    eq(board), eq(PageRequest.of(0, TaskActivityService.MAX_PAGE_SIZE)));
        }

        @Test
        @DisplayName("a board the caller cannot see answers as the board service says, which is 404")
        void anotherBoardIsNotFound() {
            when(boardService.resolve(caller, 99))
                    .thenThrow(new GlobalException(ExceptionIdentifier.BOARD_NOT_FOUND));

            assertThatThrownBy(() -> service.feed(caller, 99, 0, 25))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.BOARD_NOT_FOUND);
            verify(repository, never())
                    .findByBoardOrderByOccurredAtDescIdDesc(any(), any(Pageable.class));
        }

        @Test
        @DisplayName("an entry whose task is gone still renders, with no id to link to")
        void aDetachedEntryStillReads() {
            var entry = new TaskActivity(board, task, caller, TaskActivityType.DELETED, null);
            entry.setTask(null);
            when(repository.findByBoardOrderByOccurredAtDescIdDesc(any(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(entry)));

            var dto = service.feed(caller, 10, 0, 25).activities().getFirst();

            assertThat(dto.taskId()).isNull();
            assertThat(dto.taskTitle()).isEqualTo("Ship the release");
            assertThat(dto.type()).isEqualTo(TaskActivityType.DELETED);
        }
    }
}
