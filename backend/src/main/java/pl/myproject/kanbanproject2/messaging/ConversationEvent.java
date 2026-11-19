package pl.myproject.kanbanproject2.messaging;

public record ConversationEvent(ConversationEventType type, Long conversationId, Long seq, Integer userId) {
}
