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

    /**
     * Both halves matter. {@code clearSynchronization} drops the registered callbacks; the
     * resource the publisher binds against the thread survives it, and a leftover one makes the
     * next case collect into a set whose synchronization has already run - which is exactly the
     * failure {@code afterCompletion} exists to prevent in production, reproduced here by a test
     * that did not clean up after itself.
     */
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

        verify(messagingTemplate).convertAndSend("/topic/boards/7", new BoardEvent(BoardEventType.TASKS, 7));
    }

    @Test
    @DisplayName("inside a transaction nothing is sent until the commit")
    void waitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.tasksChanged(board);

        /*
         * The whole point of the class. A frame sent before the commit can reach a subscriber
         * whose re-read then beats the commit and returns the state from before the change -
         * leaving that client permanently stale, because it has spent its only notification.
         */
        verifyNoInteractions(messagingTemplate);

        commit();
        verify(messagingTemplate).convertAndSend("/topic/boards/7", new BoardEvent(BoardEventType.TASKS, 7));
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

        // A reorder of a twelve-card cell saves twelve tasks and needs exactly one re-read.
        for (int i = 0; i < 12; i++) {
            publisher.tasksChanged(board);
        }
        commit();

        verify(messagingTemplate).convertAndSend(eq("/topic/boards/7"), any(BoardEvent.class));
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

        verify(messagingTemplate).convertAndSend("/topic/boards/7", new BoardEvent(BoardEventType.TASKS, 7));
        verify(messagingTemplate).convertAndSend("/topic/boards/7", new BoardEvent(BoardEventType.COLUMNS, 7));
        verify(messagingTemplate).convertAndSend("/topic/boards/9", new BoardEvent(BoardEventType.ROWS, 9));
    }

    @Test
    @DisplayName("the collected set does not survive the transaction that collected it")
    void unbindsAfterCompletion() {
        TransactionSynchronizationManager.initSynchronization();
        publisher.tasksChanged(board);
        commit();
        TransactionSynchronizationManager.clearSynchronization();

        // A set left bound to a pooled thread would collect the next request's events into a
        // synchronization that has already run, and they would never be sent at all.
        TransactionSynchronizationManager.initSynchronization();
        publisher.rowsChanged(board);
        commit();

        verify(messagingTemplate).convertAndSend("/topic/boards/7", new BoardEvent(BoardEventType.ROWS, 7));
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
                .convertAndSend("/topic/boards/7", new BoardEvent(BoardEventType.TASKS, 7));
    }

    /**
     * A real commit runs both halves, in this order, and the second half is the one that lets the
     * thread be reused - so a test that only ran {@code afterCommit} would be simulating a
     * transaction that never finishes.
     */
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
