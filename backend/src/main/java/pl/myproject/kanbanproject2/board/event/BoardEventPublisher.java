package pl.myproject.kanbanproject2.board.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pl.myproject.kanbanproject2.board.Board;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tells a board's other viewers that it changed, so they re-read it instead of sitting on a screen
 * that is quietly wrong.
 *
 * <p>Shaped after {@link pl.myproject.kanbanproject2.task.activity.TaskActivityRecorder}, which is
 * called from the same places for the same reason, and it keeps that class's rule: <b>this never
 * throws.</b> A broadcast is a side effect of somebody else's operation, and a side effect that can
 * throw turns a failed notification into a failed edit. A board with no id, or a broker that
 * refuses the frame, is a viewer who finds out a little later - not a move that is rejected.
 *
 * <p><b>The frame is sent after the commit, and that is correctness rather than tidiness.</b>
 * Published inside the transaction, a frame can reach a subscriber whose {@code GET} then wins the
 * race against the commit and reads the state from <em>before</em> the change. That client is not
 * briefly stale, it is permanently stale: it has already spent the only notification it was going
 * to get. The same ordering means a transaction that rolls back broadcasts nothing, so an edit the
 * server refused does not send every other viewer to re-read a board that never changed.
 *
 * <p><b>One frame per board per transaction, however many rows moved.</b> Reordering a cell of
 * twelve cards saves twelve tasks; the subscribers' answer to all twelve is the same single
 * re-read, so the events are collected against the transaction and sent once at the end. Without
 * that, the busiest operation on the board would be the noisiest one on the wire.
 *
 * <p>Outside a transaction the frame goes immediately, because there is no commit to wait for.
 * That is a case tests exercise and the application does not reach: every service method here that
 * changes anything is {@code @Transactional}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BoardEventPublisher {

    /**
     * One destination per board rather than one topic filtered by payload: a subscriber receives
     * only the boards it asked for, and what it may ask for is decided once, on SUBSCRIBE.
     */
    public static final String DESTINATION_PREFIX = "/topic/boards/";

    /** Key for the set of events collected against the current transaction. */
    private static final String PENDING = BoardEventPublisher.class.getName() + ".pending";

    private final SimpMessagingTemplate messagingTemplate;

    public void tasksChanged(Board board) {
        publish(board, BoardEventType.TASKS);
    }

    public void columnsChanged(Board board) {
        publish(board, BoardEventType.COLUMNS);
    }

    public void rowsChanged(Board board) {
        publish(board, BoardEventType.ROWS);
    }

    private void publish(Board board, BoardEventType type) {
        if (board == null || board.getId() == null) {
            return;
        }
        var event = new BoardEvent(type, board.getId());

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send(event);
            return;
        }
        pending().add(event);
    }

    @SuppressWarnings("unchecked")
    private Set<BoardEvent> pending() {
        var existing = (Set<BoardEvent>) TransactionSynchronizationManager.getResource(PENDING);
        if (existing != null) {
            return existing;
        }

        var collected = new LinkedHashSet<BoardEvent>();
        TransactionSynchronizationManager.bindResource(PENDING, collected);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                collected.forEach(BoardEventPublisher.this::send);
            }

            /*
             * Unbound whether the transaction committed or rolled back, and unbound here rather
             * than in afterCommit: the thread goes back to a pool, and a set left bound to it
             * would collect the next request's events into a synchronization that has already run.
             */
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(PENDING);
            }
        });
        return collected;
    }

    private void send(BoardEvent event) {
        try {
            messagingTemplate.convertAndSend(DESTINATION_PREFIX + event.boardId(), event);
        } catch (RuntimeException exception) {
            log.warn("Could not announce {} on board {}: {}",
                    event.type(), event.boardId(), exception.getMessage());
        }
    }
}
