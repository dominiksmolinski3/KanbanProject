package pl.myproject.kanbanproject2.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Collection;

@Slf4j
@Component
@RequiredArgsConstructor
public class GroupRestarts {

    private final ConversationRepository conversations;
    private final ConversationDeviceRepository holders;
    private final ConversationMemberRepository members;
    private final ConversationLocks locks;
    private final ConversationNotifier notifier;
    private final SecureRandom random = new SecureRandom();

    public void restartOrphans(Collection<Long> conversationIds) {
        for (Long conversationId : conversationIds) {
            if (!conversations.existsById(conversationId) || holders.hasLiveHolder(conversationId)) {
                continue;
            }
            Conversation conversation = locks.lock(conversationId);
            if (conversation.getEpoch() == 0) {
                continue;
            }
            byte[] groupId = new byte[ConversationService.GROUP_ID_BYTES];
            random.nextBytes(groupId);
            conversation.restart(groupId);
            conversations.save(conversation);
            holders.forgetAll(conversationId);
            notifier.notify(members.usersIn(conversationId),
                    new ConversationEvent(ConversationEventType.MEMBERS, conversationId, null, null));
            log.info("Restarted conversation {}: no live device of a member still held its group", conversationId);
        }
    }
}
