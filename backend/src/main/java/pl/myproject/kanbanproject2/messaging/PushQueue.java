package pl.myproject.kanbanproject2.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PushQueue {

    private final List<PushSender> senders;
    private final PushEndpointRepository endpoints;
    private final PushOutboxRepository outbox;
    private final Clock clock;

    @Autowired
    public PushQueue(List<PushSender> senders, PushEndpointRepository endpoints, PushOutboxRepository outbox) {
        this(senders, endpoints, outbox, Clock.systemUTC());
    }

    PushQueue(List<PushSender> senders, PushEndpointRepository endpoints, PushOutboxRepository outbox, Clock clock) {
        this.senders = senders;
        this.endpoints = endpoints;
        this.outbox = outbox;
        this.clock = clock;
    }

    public void messagePosted(Conversation conversation, long seq, Integer senderUserId, List<ConversationMember> members) {
        Set<PushKind> live = senders.stream().filter(PushSender::configured).map(PushSender::kind)
                .collect(Collectors.toSet());
        if (live.isEmpty()) {
            return;
        }
        Set<Integer> recipients = members.stream()
                .filter(member -> member.getState() == MemberState.ACTIVE && !member.isMuted()
                        && !member.getUser().getId().equals(senderUserId))
                .map(member -> member.getUser().getId())
                .collect(Collectors.toSet());
        if (recipients.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Map<Long, PushEndpoint> byId = endpoints.findOfUsers(recipients).stream()
                .filter(endpoint -> live.contains(endpoint.getKind()))
                .collect(Collectors.toMap(PushEndpoint::getId, endpoint -> endpoint));
        byId.keySet().forEach(id -> outbox.enqueue(id, conversation.getId(), seq, now));
    }
}
