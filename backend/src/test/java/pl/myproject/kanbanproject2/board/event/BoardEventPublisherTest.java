package pl.myproject.kanbanproject2.board.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pl.myproject.kanbanproject2.board.Board;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class BoardEventPublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private BoardEventPublisher publisher;

    private Board board;

    @BeforeEach
    void setUp() {
        board = new Board();
        board.setId(7);
    }

    @AfterEach
    void clearAnyTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        List.copyOf(TransactionSynchronizationManager.getResourceMap().keySet())
                .forEach(TransactionSynchronizationManager::unbindResourceIfPossible);
    }

    @Test
    @DisplayName("outside a transaction the frame goes straight out")
    void sendsImmediatelyWithNoTransaction() {
        publisher.tasksChanged(board);

        verify(messagingTemplate).convertAndSend("/topic/boards.7", new BoardEvent(BoardEventType.TASKS, 7));
    }

    @Test
    @DisplayName("inside a transaction nothing is sent until the commit")
    void waitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.tasksChanged(board);

        verifyNoInteractions(messagingTemplate);

        commit();
        verify(messagingTemplate).convertAndSend("/topic/boards.7", new BoardEvent(BoardEventType.TASKS, 7));
    }

    @Test
    @DisplayName("a rolled back transaction announces nothing")
    void aRollbackAnnouncesNothing() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.columnsChanged(board);
        synchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("many saves of one kind on one board are one frame")
    void coalescesWithinATransaction() {
        TransactionSynchronizationManager.initSynchronization();

        for (int i = 0; i < 12; i++) {
            publisher.tasksChanged(board);
        }
        commit();

        verify(messagingTemplate).convertAndSend(eq("/topic/boards.7"), any(BoardEvent.class));
    }

    @Test
    @DisplayName("different kinds and different boards are kept apart")
    void doesNotCoalesceAcrossKindsOrBoards() {
        var other = new Board();
        other.setId(9);

        TransactionSynchronizationManager.initSynchronization();
        publisher.tasksChanged(board);
        publisher.columnsChanged(board);
        publisher.rowsChanged(other);
        commit();

        verify(messagingTemplate).convertAndSend("/topic/boards.7", new BoardEvent(BoardEventType.TASKS, 7));
        verify(messagingTemplate).convertAndSend("/topic/boards.7", new BoardEvent(BoardEventType.COLUMNS, 7));
        verify(messagingTemplate).convertAndSend("/topic/boards.9", new BoardEvent(BoardEventType.ROWS, 9));
    }

    @Test
    @DisplayName("the collected set does not survive the transaction that collected it")
    void unbindsAfterCompletion() {
        TransactionSynchronizationManager.initSynchronization();
        publisher.tasksChanged(board);
        commit();
        TransactionSynchronizationManager.clearSynchronization();

        TransactionSynchronizationManager.initSynchronization();
        publisher.rowsChanged(board);
        commit();

        verify(messagingTemplate).convertAndSend("/topic/boards.7", new BoardEvent(BoardEventType.ROWS, 7));
    }

    @Test
    @DisplayName("a board with no id is not announced")
    void ignoresAnUnsavedBoard() {
        publisher.tasksChanged(new Board());
        publisher.tasksChanged(null);

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("a broker that refuses the frame does not fail the edit that caused it")
    void swallowsABrokerFailure() {
        doThrow(new IllegalStateException("broker is gone"))
                .when(messagingTemplate).convertAndSend(any(String.class), any(Object.class));

        publisher.tasksChanged(board);

        verify(messagingTemplate).convertAndSend(any(String.class), any(Object.class));
    }

    @Test
    @DisplayName("nothing is announced for a kind nobody raised")
    void announcesOnlyWhatHappened() {
        publisher.rowsChanged(board);

        verify(messagingTemplate, never())
                .convertAndSend("/topic/boards.7", new BoardEvent(BoardEventType.TASKS, 7));
    }

    @Test
    @DisplayName("a boards notice goes to each person's own queue once, after the commit")
    void notifiesEachPersonAfterTheCommit() {
        TransactionSynchronizationManager.initSynchronization();
        var ada = person(1, "ada@example.test");
        var bob = person(2, "bob@example.test");

        publisher.onBoardsChanged(new BoardsChanged(List.of(ada, bob)));
        publisher.boardsChangedFor(List.of(ada));
        verifyNoInteractions(messagingTemplate);

        commit();
        var notice = new BoardEvent(BoardEventType.BOARDS, null);
        verify(messagingTemplate).convertAndSendToUser("ada@example.test", BoardEventPublisher.BOARDS_QUEUE, notice);
        verify(messagingTemplate).convertAndSendToUser("bob@example.test", BoardEventPublisher.BOARDS_QUEUE, notice);
    }

    @Test
    @DisplayName("a rolled back change tells nobody their boards changed")
    void aRollbackNotifiesNobody() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.boardsChangedFor(List.of(person(1, "ada@example.test")));
        synchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("outside a transaction a boards notice goes straight out, and nobody without a name is sent one")
    void notifiesImmediatelyWithNoTransaction() {
        publisher.boardsChangedFor(java.util.Arrays.asList(person(1, "ada@example.test"), null, person(3, null)));

        verify(messagingTemplate).convertAndSendToUser("ada@example.test", BoardEventPublisher.BOARDS_QUEUE,
                new BoardEvent(BoardEventType.BOARDS, null));
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("a broker that refuses a notice is logged, and the change it describes still stands")
    void aRefusedNoticeDoesNotThrow() {
        doThrow(new org.springframework.messaging.MessageDeliveryException("broker down"))
                .when(messagingTemplate).convertAndSendToUser(any(), any(), any());

        org.assertj.core.api.Assertions.assertThatCode(() ->
                publisher.boardsChangedFor(List.of(person(1, "ada@example.test")))).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() -> publisher.boardsChangedFor(null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the event copies the people it is given, so a later change to the board does not change who is told")
    void theEventTakesACopy() {
        var people = new java.util.ArrayList<>(List.of(person(1, "ada@example.test")));
        var event = BoardsChanged.forEveryoneIn(people);
        people.clear();

        assertThat(event.people()).hasSize(1);
    }

    private static pl.myproject.kanbanproject2.user.User person(int id, String email) {
        var user = new pl.myproject.kanbanproject2.user.User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    private static void commit() {
        var registered = synchronizations();
        registered.forEach(TransactionSynchronization::afterCommit);
        registered.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    private static List<TransactionSynchronization> synchronizations() {
        var registered = List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        assertThat(registered).as("nothing registered itself against the transaction").isNotEmpty();
        return registered;
    }
}
