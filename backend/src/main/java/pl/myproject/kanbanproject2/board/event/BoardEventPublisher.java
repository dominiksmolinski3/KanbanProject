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
 * that is quietly wrong. Shaped after
 * {@link pl.myproject.kanbanproject2.task.activity.TaskActivityRecorder} and keeps its rule:
 * <b>this never throws</b> — a board with no id, or a broker that refuses the frame, is a viewer
 * who finds out a little later, not a move that gets rejected.
 *
 * <p><b>The frame is sent after the commit</b>, or a subscriber's re-read could win the race against
 * the commit and read stale state permanently, having already spent its one notification; the same
 * ordering means a rolled-back transaction broadcasts nothing.
 *
 * <p><b>One frame per board per transaction, however many rows moved</b> — events are collected
 * against the transaction and sent once at the end, so reordering twelve cards doesn't send twelve
 * frames for a burst the client would coalesce into one re-read anyway.
 *
 * <p>Outside a transaction the frame goes immediately; the application never hits this since every
 * mutating service method here is {@code @Transactional}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BoardEventPublisher {

    /**
     * One destination per board rather than one topic filtered by payload: a subscriber receives
     * only the boards it asked for, and what it may ask for is decided once, on SUBSCRIBE. A dot,
     * not a slash: the STOMP broker relay forwards this string to RabbitMQ's STOMP plugin as-is,
     * and RabbitMQ parses everything after {@code /topic/} as one AMQP topic-exchange routing key -
     * a routing key may contain dots (that's how {@code *}/{@code #} wildcards would work if this
     * ever needed them), but a further {@code /} makes the whole destination invalid and the
     * SUBSCRIBE is refused with a STOMP ERROR frame that closes the connection. Spring's in-JVM
     * {@code enableSimpleBroker} never enforced this, so it was invisible until the broker relay
     * replaced it - see {@code /topic/room.} in {@code WebSocketEventListener}, which already used
     * a dot for the same reason.
     */
    public static final String DESTINATION_PREFIX = "/topic/boards.";

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
