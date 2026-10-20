package pl.myproject.kanbanproject2.board.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.user.User;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class BoardEventPublisher {

    // A dot, not a slash: RabbitMQ's STOMP plugin refuses a destination with a further '/' after /topic/.
    public static final String DESTINATION_PREFIX = "/topic/boards.";

    public static final String BOARDS_QUEUE = "/queue/boards";

    private static final String PENDING = BoardEventPublisher.class.getName() + ".pending";

    private record Pending(Set<BoardEvent> boardEvents, Set<String> notified) {
    }

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

    // An event rather than a call: BoardService cannot depend on this bean, which the socket config depends on through it.
    @EventListener
    public void onBoardsChanged(BoardsChanged event) {
        boardsChangedFor(event.people());
    }

    public void boardsChangedFor(Collection<User> people) {
        if (people == null) {
            return;
        }
        for (User person : people) {
            if (person == null || person.getUsername() == null) {
                continue;
            }
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                notify(person.getUsername());
            } else {
                pending().notified().add(person.getUsername());
            }
        }
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
        pending().boardEvents().add(event);
    }

    private Pending pending() {
        var existing = (Pending) TransactionSynchronizationManager.getResource(PENDING);
        if (existing != null) {
            return existing;
        }

        var collected = new Pending(new LinkedHashSet<>(), new LinkedHashSet<>());
        TransactionSynchronizationManager.bindResource(PENDING, collected);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                collected.boardEvents().forEach(BoardEventPublisher.this::send);
                collected.notified().forEach(BoardEventPublisher.this::notify);
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(PENDING);
            }
        });
        return collected;
    }

    private void notify(String username) {
        try {
            messagingTemplate.convertAndSendToUser(username, BOARDS_QUEUE, new BoardEvent(BoardEventType.BOARDS, null));
        } catch (RuntimeException exception) {
            log.warn("Could not tell {} their boards changed: {}", username, exception.getMessage());
        }
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
