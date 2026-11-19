package pl.myproject.kanbanproject2.messaging;

import java.util.List;

public record ConversationView(ConversationMember me, List<ConversationMember> members, long unread) {
}
