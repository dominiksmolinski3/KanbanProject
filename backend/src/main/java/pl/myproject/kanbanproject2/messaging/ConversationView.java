package pl.myproject.kanbanproject2.messaging;

import java.util.List;

public record ConversationView(ConversationMember me, List<ConversationMember> members, long unread,
                               String boardName) {

    public ConversationView(ConversationMember me, List<ConversationMember> members, long unread) {
        this(me, members, unread, null);
    }
}
