package pl.myproject.kanbanproject2.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pl.myproject.kanbanproject2.user.User;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class ConversationNotifier {

    public static final String QUEUE = "/queue/conversations";

    private static final String PENDING = ConversationNotifier.class.getName() + ".pending";

    private record Frame(String username, ConversationEvent event) {
    }

    private final SimpMessagingTemplate messagingTemplate;

    public void notify(Collection<User> people, ConversationEvent event) {
        for (User person : people) {
            if (person == null || person.getUsername() == null) {
                continue;
            }
            Frame frame = new Frame(person.getUsername(), event);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                pending().add(frame);
            } else {
                send(frame);
            }
        }
    }

    private Set<Frame> pending() {
        @SuppressWarnings("unchecked")
        var existing = (Set<Frame>) TransactionSynchronizationManager.getResource(PENDING);
        if (existing != null) {
            return existing;
        }
        Set<Frame> collected = new LinkedHashSet<>();
        TransactionSynchronizationManager.bindResource(PENDING, collected);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                collected.forEach(ConversationNotifier.this::send);
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(PENDING);
            }
        });
        return collected;
    }

    private void send(Frame frame) {
        try {
            messagingTemplate.convertAndSendToUser(frame.username(), QUEUE, frame.event());
        } catch (RuntimeException exception) {
            log.warn("Could not send a {} notice for conversation {}: {}",
                    frame.event().type(), frame.event().conversationId(), exception.getMessage());
        }
    }
}
