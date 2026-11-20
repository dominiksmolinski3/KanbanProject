package pl.myproject.kanbanproject2.messaging;

import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class ConversationMapper implements Function<ConversationView, ConversationDto> {

    @Override
    public ConversationDto apply(ConversationView view) {
        ConversationMember me = view.me();
        Conversation conversation = me.getConversation();
        return new ConversationDto(conversation.getId(), conversation.getKind(), conversation.getBoardId(),
                view.boardName(), conversation.getGroupId(),
                conversation.getEpoch(), conversation.isNeedsCommit(), conversation.getHistoryVisibility(),
                conversation.getWhoCanAdd(), conversation.getLastSeq(), conversation.getCreatedAt(),
                conversation.getLastActivityAt(), view.unread(),
                new MembershipDto(me.getRole(), me.getState(), me.getJoinedSeq(), me.getDeliveredSeq(), me.getReadSeq(),
                        me.isMuted(),
                        me.isPinned(), me.isArchived()),
                view.members().stream()
                        .map(member -> new MemberDto(member.getUser().getId(), member.getUser().getName(),
                                member.getUser().getAvatarBlobName() != null, member.getRole(), member.getState(),
                                member.getJoinedAt(), member.getJoinedSeq(), member.getDeliveredSeq(),
                                member.getReadSeq()))
                        .toList());
    }
}
