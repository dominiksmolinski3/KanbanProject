package pl.myproject.kanbanproject2.messaging;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

@Component
public class ConversationLocks {

    @PersistenceContext
    private EntityManager entityManager;

    public Conversation lock(Long conversationId) {
        Conversation conversation = entityManager.find(Conversation.class, conversationId);
        if (conversation == null) {
            throw ConversationAccess.conversationNotFound();
        }
        entityManager.refresh(conversation, LockModeType.PESSIMISTIC_WRITE);
        return conversation;
    }
}
