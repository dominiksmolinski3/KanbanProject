package pl.myproject.kanbanproject2.messaging;

import org.springframework.test.util.ReflectionTestUtils;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;
import java.util.UUID;

final class MessagingFixtures {

    static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private MessagingFixtures() {
    }

    static User user(int id, String name) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setEmail(name.toLowerCase() + "@example.com");
        return user;
    }

    static Conversation conversation(long id, ConversationKind kind, User creator) {
        Conversation conversation = new Conversation(kind, kind == ConversationKind.DIRECT ? "1:2" : null,
                new byte[16], HistoryVisibility.JOINED, WhoCanAdd.ADMINS, creator, NOW);
        ReflectionTestUtils.setField(conversation, "id", id);
        return conversation;
    }

    static ConversationMember member(long id, Conversation conversation, User user, MemberRole role, MemberState state,
                                     Instant joinedAt) {
        ConversationMember member = new ConversationMember(conversation, user, role, state, joinedAt);
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    static IdentityKey identity(User user) {
        IdentityKey identity = new IdentityKey(user, new byte[32], null, NOW);
        ReflectionTestUtils.setField(identity, "userId", user.getId());
        return identity;
    }

    static CryptoDevice device(long id, User user) {
        CryptoDevice device = new CryptoDevice(user, UUID.randomUUID(), new byte[32],
                ("u" + user.getId() + ".0123456789abcdef0123456789abcdef").getBytes(), new byte[64], "Firefox", NOW);
        ReflectionTestUtils.setField(device, "id", id);
        return device;
    }
}
