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

@Component
@RequiredArgsConstructor
@Slf4j
public class BoardEventPublisher {

    // A dot, not a slash: RabbitMQ's STOMP plugin refuses a destination with a further '/' after /topic/.
    public static final String DESTINATION_PREFIX = "/topic/boards.";

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

    public void commentsChanged(Board board) {
        publish(board, BoardEventType.COMMENTS);
    }

    public void subtasksChanged(Board board) {
        publish(board, BoardEventType.SUBTASKS);
    }

    public void attachmentsChanged(Board board) {
        publish(board, BoardEventType.ATTACHMENTS);
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
