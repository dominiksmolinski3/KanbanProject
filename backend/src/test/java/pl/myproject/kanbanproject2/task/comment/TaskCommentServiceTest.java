package pl.myproject.kanbanproject2.task.comment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.BoardTasksDeleting;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.activity.TaskActivityRecorder;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who may read a card's thread, who may write in it, and who may change what was written. The task
 * is the only thing that grants access, so a comment id from another board, presented under a task
 * the caller can see, must be a 404 or the task in the path is decoration. Every write announces
 * the board, since a thread nobody else's panel re-reads is a thread only its author can see live.
 */
class TaskCommentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:15:30Z");

    private TaskCommentRepository comments;
    private TaskRepository tasks;
    private BoardService boardService;
    private BoardEventPublisher boardEvents;
    private TaskActivityRecorder activityRecorder;
    private TaskCommentService service;

    private User owner;
    private User member;
    private User stranger;
    private Board board;
    private Task task;

    @BeforeEach
    void setUp() {
        comments = mock(TaskCommentRepository.class);
        tasks = mock(TaskRepository.class);
        boardEvents = mock(BoardEventPublisher.class);
        activityRecorder = mock(TaskActivityRecorder.class);

        var tenant = TenancyFixtures.tenant();
        owner = tenant.caller();
        board = tenant.board();
        boardService = tenant.boardService();
        member = TenancyFixtures.user(2);
        board.getMembers().add(member);
        stranger = TenancyFixtures.user(99);

        task = taskOn(board, 42);
        when(tasks.findById(42)).thenReturn(Optional.of(task));
        when(comments.save(any(TaskComment.class))).thenAnswer(call -> {
            TaskComment saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(1L);
            }
            return saved;
        });

        service = new TaskCommentService(comments, tasks, new TaskCommentMapper(), boardService,
                boardEvents, activityRecorder, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Task taskOn(Board board, int id) {
        var task = new Task();
        task.setId(id);
        task.setTitle("Card " + id);
        task.setBoard(board);
        return task;
    }

    private static TaskComment comment(Task task, long id, User author) {
        var comment = new TaskComment();
        comment.setId(id);
        comment.setTask(task);
        comment.setAuthor(author);
        comment.setBody("words");
        comment.setCreatedAt(NOW.minusSeconds(60));
        return comment;
    }

    @Nested
    @DisplayName("reading the thread")
    class Reading {

        @Test
        @DisplayName("pages newest first with the default size, and reports the total")
        void pagesNewestFirst() {
            var older = comment(task, 1L, member);
            var newer = comment(task, 2L, owner);
            when(comments.findByTaskOrderByCreatedAtDescIdDesc(eq(task), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(newer, older), PageRequest.of(0, 25), 2));

            var results = service.thread(owner, 42, null, null);

            assertThat(results.comments()).extracting(TaskCommentDto::id).containsExactly(2L, 1L);
            assertThat(results.size()).isEqualTo(TaskCommentService.DEFAULT_PAGE_SIZE);
            assertThat(results.totalEntries()).isEqualTo(2);
            verify(comments).findByTaskOrderByCreatedAtDescIdDesc(task, PageRequest.of(0, 25));
        }

        @Test
        @DisplayName("refuses a size over the maximum rather than clamping it")
        void refusesAnOversizedPage() {
            assertThatThrownBy(() -> service.thread(owner, 42, 0, TaskCommentService.MAX_PAGE_SIZE + 1))
                    .isInstanceOfSatisfying(GlobalException.class, e ->
                            assertThat(e.getIdentifier()).isEqualTo(ExceptionIdentifier.INVALID_COMMENT_REQUEST));
            assertThatThrownBy(() -> service.thread(owner, 42, -1, 10))
                    .isInstanceOf(GlobalException.class);
        }

        @Test
        @DisplayName("a stranger gets the task's 404, not the thread")
        void strangerGetsA404() {
            assertThatThrownBy(() -> service.thread(stranger, 42, 0, 25))
                    .isInstanceOfSatisfying(GlobalException.class, e ->
                            assertThat(e.getIdentifier()).isEqualTo(ExceptionIdentifier.TASK_NOT_FOUND));
            verifyNoInteractions(comments);
        }

        @Test
        @DisplayName("a viewer reads it: the write check is never asked on the read path")
        void viewerReads() {
            doThrow(new GlobalException(ExceptionIdentifier.VIEWER_READ_ONLY))
                    .when(boardService).requireWritable(any(User.class), any(Board.class));
            when(comments.findByTaskOrderByCreatedAtDescIdDesc(eq(task), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of()));

            assertThat(service.thread(member, 42, 0, 25).comments()).isEmpty();
        }
    }

    @Nested
    @DisplayName("writing")
    class Writing {

        @Test
        @DisplayName("stores the trimmed text against the caller, records it in the feed and announces it")
        void addsAndAnnounces() {
            var added = service.add(member, 42, new TaskCommentRequest("  looks good  "));

            assertThat(added.body()).isEqualTo("looks good");
            assertThat(added.authorId()).isEqualTo(member.getId());
            assertThat(added.createdAt()).isEqualTo(NOW);
            assertThat(added.editedAt()).isNull();
            verify(activityRecorder).commented(member, task);
            verify(boardEvents).commentsChanged(board);
        }

        @Test
        @DisplayName("a viewer cannot comment, and nothing is written or announced")
        void viewerCannotComment() {
            var readOnly = new GlobalException(ExceptionIdentifier.VIEWER_READ_ONLY);
            doThrow(readOnly).when(boardService).requireWritable(any(User.class), any(Board.class));

            assertThatThrownBy(() -> service.add(member, 42, new TaskCommentRequest("hi"))).isSameAs(readOnly);

            verify(comments, never()).save(any());
            verifyNoInteractions(boardEvents, activityRecorder);
        }

        @Test
        @DisplayName("a stranger cannot comment on a task they cannot see")
        void strangerCannotComment() {
            assertThatThrownBy(() -> service.add(stranger, 42, new TaskCommentRequest("hi")))
                    .isInstanceOfSatisfying(GlobalException.class, e ->
                            assertThat(e.getIdentifier()).isEqualTo(ExceptionIdentifier.TASK_NOT_FOUND));
            verify(comments, never()).save(any());
        }
    }

    @Nested
    @DisplayName("editing")
    class Editing {

        @Test
        @DisplayName("the author may rewrite their comment, which is then marked as edited")
        void authorEdits() {
            when(comments.findById(1L)).thenReturn(Optional.of(comment(task, 1L, member)));

            var edited = service.edit(member, 42, 1L, new TaskCommentRequest("better words"));

            assertThat(edited.body()).isEqualTo("better words");
            assertThat(edited.editedAt()).isEqualTo(NOW);
            verify(boardEvents).commentsChanged(board);
        }

        @Test
        @DisplayName("nobody else may, the board's owner included - a 403, since they can see it")
        void onlyTheAuthorEdits() {
            when(comments.findById(1L)).thenReturn(Optional.of(comment(task, 1L, member)));

            assertThatThrownBy(() -> service.edit(owner, 42, 1L, new TaskCommentRequest("x")))
                    .isInstanceOfSatisfying(GlobalException.class, e ->
                            assertThat(e.getIdentifier()).isEqualTo(ExceptionIdentifier.NOT_COMMENT_AUTHOR));
            verify(comments, never()).save(any());
        }

        @Test
        @DisplayName("authorship is compared by id, not instance, since the caller comes from the token")
        void authorshipIsById() {
            when(comments.findById(1L)).thenReturn(Optional.of(comment(task, 1L, TenancyFixtures.user(2))));

            assertThat(service.edit(member, 42, 1L, new TaskCommentRequest("same person")).body())
                    .isEqualTo("same person");
        }

        @Test
        @DisplayName("a comment whose author has gone can be edited by nobody")
        void orphanedCommentIsNobodys() {
            when(comments.findById(1L)).thenReturn(Optional.of(comment(task, 1L, null)));

            assertThatThrownBy(() -> service.edit(member, 42, 1L, new TaskCommentRequest("x")))
                    .isInstanceOf(GlobalException.class);
        }
    }

    @Nested
    @DisplayName("deleting")
    class Deleting {

        @Test
        @DisplayName("the author may remove their own comment, and the board is told")
        void authorDeletes() {
            var mine = comment(task, 1L, member);
            when(comments.findById(1L)).thenReturn(Optional.of(mine));

            service.delete(member, 42, 1L);

            verify(comments).delete(mine);
            verify(boardEvents).commentsChanged(board);
        }

        @Test
        @DisplayName("the board's owner may remove anybody's")
        void ownerModerates() {
            var theirs = comment(task, 1L, member);
            when(comments.findById(1L)).thenReturn(Optional.of(theirs));

            service.delete(owner, 42, 1L);

            verify(comments).delete(theirs);
        }

        @Test
        @DisplayName("another member may not remove somebody else's")
        void memberCannotRemoveAnothers() {
            var third = TenancyFixtures.user(3);
            board.getMembers().add(third);
            when(comments.findById(1L)).thenReturn(Optional.of(comment(task, 1L, member)));

            assertThatThrownBy(() -> service.delete(third, 42, 1L))
                    .isInstanceOfSatisfying(GlobalException.class, e ->
                            assertThat(e.getIdentifier()).isEqualTo(ExceptionIdentifier.NOT_COMMENT_AUTHOR));
            verify(comments, never()).delete(any());
            verifyNoInteractions(boardEvents);
        }

        @Test
        @DisplayName("a comment from another board, named under a task the caller can see, is a 404")
        void commentFromAnotherBoardIs404() {
            var elsewhere = taskOn(TenancyFixtures.board(5, stranger), 7);
            when(comments.findById(9L)).thenReturn(Optional.of(comment(elsewhere, 9L, stranger)));

            assertThatThrownBy(() -> service.delete(owner, 42, 9L))
                    .isInstanceOfSatisfying(GlobalException.class, e ->
                            assertThat(e.getIdentifier()).isEqualTo(ExceptionIdentifier.COMMENT_NOT_FOUND));
            verify(comments, never()).delete(any());
        }

        @Test
        @DisplayName("an unknown id is the same 404")
        void unknownIdIs404() {
            when(comments.findById(9L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(owner, 42, 9L))
                    .isInstanceOfSatisfying(GlobalException.class, e ->
                            assertThat(e.getIdentifier()).isEqualTo(ExceptionIdentifier.COMMENT_NOT_FOUND));
        }

        @Test
        @DisplayName("the whole thread goes with its card")
        void cascadeForTheCard() {
            var thread = List.of(comment(task, 1L, member), comment(task, 2L, owner));
            when(comments.findByTask(task)).thenReturn(thread);

            service.deleteAllFor(task);

            verify(comments).deleteAll(thread);
        }

        @Test
        @DisplayName("every thread on a board being deleted goes before its cards do")
        void boardDeletionTakesEveryThread() {
            var other = taskOn(board, 43);
            var cards = List.of(task, other);
            var threads = List.of(comment(task, 1L, member), comment(other, 2L, owner));
            when(comments.findByTaskIn(cards)).thenReturn(threads);

            service.onBoardTasksDeleting(new BoardTasksDeleting(board, cards));

            verify(comments).deleteAll(threads);
        }

        @Test
        @DisplayName("the board listener runs inside deleteBoard's transaction, not after it")
        void listenerIsSynchronous() throws NoSuchMethodException {
            var listener = TaskCommentService.class.getMethod("onBoardTasksDeleting", BoardTasksDeleting.class);

            assertThat(listener.isAnnotationPresent(org.springframework.context.event.EventListener.class)).isTrue();
            assertThat(listener.isAnnotationPresent(
                    org.springframework.transaction.event.TransactionalEventListener.class)).isFalse();
        }

        @Test
        @DisplayName("a card with no thread deletes nothing")
        void emptyCascade() {
            when(comments.findByTask(task)).thenReturn(List.of());

            service.deleteAllFor(task);

            verify(comments, never()).deleteAll(any());
        }
    }

    @Test
    @DisplayName("the mapper names no author for a comment whose account has gone")
    void mapperToleratesAMissingAuthor() {
        var dto = new TaskCommentMapper().apply(comment(task, 1L, null));

        assertThat(dto.authorId()).isNull();
        assertThat(dto.authorName()).isNull();
        assertThat(new TaskCommentMapper().apply(null)).isNull();
    }
}
