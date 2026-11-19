package pl.myproject.kanbanproject2.messaging;

public record WelcomeDto(Long id, Long conversationId, long seq, byte[] body) {
}
