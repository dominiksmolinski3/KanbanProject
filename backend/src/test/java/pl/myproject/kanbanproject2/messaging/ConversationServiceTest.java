package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitDecision;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.NOW;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.conversation;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.identity;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.member;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.user;

class ConversationServiceTest {

    private ConversationAccess access;
    private ConversationRepository conversations;
    private ConversationLocks locks;
    private ConversationMemberRepository members;
    private MlsMessageRepository messages;
    private UserBlockRepository blocks;
    private IdentityKeyRepository identities;
    private CryptoDeviceRepository devices;
    private UserRepository users;
    private BoardService boards;
    private ConversationNotifier notifier;
    private AuthRateLimiter rateLimiter;
    private GroupRestarts restarts;
    private ConversationService service;

    private final User ania = user(1, "Ania");
    private final User bartek = user(2, "Bartek");
    private final User celina = user(3, "Celina");

    @BeforeEach
    void setUp() {
        access = mock(ConversationAccess.class);
        conversations = mock(ConversationRepository.class);
        locks = mock(ConversationLocks.class);
        members = mock(ConversationMemberRepository.class);
        messages = mock(MlsMessageRepository.class);
        blocks = mock(UserBlockRepository.class);
        identities = mock(IdentityKeyRepository.class);
        devices = mock(CryptoDeviceRepository.class);
        users = mock(UserRepository.class);
        boards = mock(BoardService.class);
        notifier = mock(ConversationNotifier.class);
        rateLimiter = mock(AuthRateLimiter.class);
        restarts = mock(GroupRestarts.class);
        service = new ConversationService(access, conversations, locks, members, messages, blocks, identities, devices,
                users, boards, notifier, new ConversationMapper(), rateLimiter, restarts, Clock.fixed(NOW, ZoneOffset.UTC));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(members.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(rateLimiter.tryConsume(any(), any(), any())).thenReturn(new AuthRateLimitDecision(true, Duration.ZERO));
        for (User each : List.of(ania, bartek, celina)) {
            when(users.findById(each.getId())).thenReturn(Optional.of(each));
        }
        ready(ania, bartek, celina);
    }

    private void ready(User... people) {
        doAnswer(invocation -> {
            List<IdentityKey> found = new ArrayList<>();
            for (Object id : (Iterable<?>) invocation.getArgument(0)) {
                for (User person : people) {
                    if (person.getId().equals(id)) {
                        found.add(identity(person));
                    }
                }
            }
            return found;
        }).when(identities).findAllById(any());
        doAnswer(invocation -> List.copyOf(invocation.<Collection<Integer>>getArgument(0)))
                .when(devices).usersWithLiveDevices(anyCollection());
    }

    private ConversationMember joined(long id, Conversation conversation, User person, MemberRole role, MemberState state) {
        return member(id, conversation, person, role, state, NOW.plusSeconds(id));
    }

    private void inConversation(Conversation conversation, ConversationMember... everyone) {
        when(members.findAllIn(List.of(conversation))).thenReturn(List.of(everyone));
        for (ConversationMember each : everyone) {
            when(members.findMembership(conversation.getId(), each.getUser().getId())).thenReturn(Optional.of(each));
        }
    }

    private void callerIs(ConversationMember me) {
        when(access.requireMember(me.getUser(), me.getConversation().getId())).thenReturn(me);
    }

    @Nested
    class StartingAConversation {

        @Test
        @DisplayName("a direct conversation with someone from a shared board starts active for both")
        void directWithAPeer() {
            when(boards.shareABoard(ania, 2)).thenReturn(true);

            var created = service.create(ania, new CreateConversationRequest(ConversationKind.DIRECT, List.of(2), null, null));

            ArgumentCaptor<ConversationMember> saved = ArgumentCaptor.forClass(ConversationMember.class);
            verify(members, times(2)).save(saved.capture());
            assertThat(created.created()).isTrue();
            assertThat(saved.getAllValues()).extracting(ConversationMember::getState)
                    .containsExactly(MemberState.ACTIVE, MemberState.ACTIVE);
            ArgumentCaptor<Conversation> conversation = ArgumentCaptor.forClass(Conversation.class);
            verify(conversations).save(conversation.capture());
            assertThat(conversation.getValue().getDirectKey()).isEqualTo("1:2");
            assertThat(conversation.getValue().getGroupId()).hasSize(16);
            verify(rateLimiter, never()).tryConsume(any(), any(), any());
        }

        @Test
        @DisplayName("a stranger receives a request, and reaching a stranger is rate limited")
        void directWithAStranger() {
            service.create(ania, new CreateConversationRequest(ConversationKind.DIRECT, List.of(2), null, null));

            ArgumentCaptor<ConversationMember> saved = ArgumentCaptor.forClass(ConversationMember.class);
            verify(members, times(2)).save(saved.capture());
            assertThat(saved.getAllValues().get(1).getState()).isEqualTo(MemberState.REQUEST);
            verify(rateLimiter).tryConsume(any(), any(), eq("messaging-reach:1"));
        }

        @Test
        @DisplayName("asking again for the same pair hands back the conversation that exists")
        void directTwice() {
            Conversation existing = conversation(9, ConversationKind.DIRECT, ania);
            ConversationMember me = joined(1, existing, ania, MemberRole.ADMIN, MemberState.ACTIVE);
            when(conversations.findByDirectKey("1:2")).thenReturn(Optional.of(existing));
            when(boards.shareABoard(ania, 2)).thenReturn(true);
            callerIs(me);
            inConversation(existing, me, joined(2, existing, bartek, MemberRole.ADMIN, MemberState.ACTIVE));

            var created = service.create(ania, new CreateConversationRequest(ConversationKind.DIRECT, List.of(2), null, null));

            assertThat(created.created()).isFalse();
            assertThat(created.conversation().id()).isEqualTo(9L);
            verify(conversations, never()).save(any());
        }

        @Test
        @DisplayName("a direct conversation needs exactly one other person")
        void directNeedsOneOther() {
            assertThatThrownBy(() -> service.create(ania,
                    new CreateConversationRequest(ConversationKind.DIRECT, List.of(1), null, null)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
            assertThatThrownBy(() -> service.create(ania,
                    new CreateConversationRequest(ConversationKind.DIRECT, List.of(2, 3), null, null)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
        }

        @Test
        @DisplayName("someone who blocked you, or whom you blocked, looks like nobody at all")
        void blockedLooksUnknown() {
            when(blocks.eitherBlocks(1, 2)).thenReturn(true);

            assertThatThrownBy(() -> service.create(ania,
                    new CreateConversationRequest(ConversationKind.DIRECT, List.of(2), null, null)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("a peer without messages is told apart, a stranger without them is not")
        void notSetUp() {
            ready(ania);
            when(boards.shareABoard(ania, 2)).thenReturn(true);

            assertThatThrownBy(() -> service.create(ania,
                    new CreateConversationRequest(ConversationKind.DIRECT, List.of(2), null, null)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.RECIPIENT_NOT_SET_UP);
            assertThatThrownBy(() -> service.create(ania,
                    new CreateConversationRequest(ConversationKind.DIRECT, List.of(3), null, null)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("the caller must have set up messages first")
        void callerNotSetUp() {
            ready(bartek);

            assertThatThrownBy(() -> service.create(ania,
                    new CreateConversationRequest(ConversationKind.DIRECT, List.of(2), null, null)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND);
        }

        @Test
        @DisplayName("reaching strangers too often is refused")
        void strangersRateLimited() {
            when(rateLimiter.tryConsume(any(), any(), any())).thenReturn(new AuthRateLimitDecision(false, Duration.ofMinutes(1)));

            assertThatThrownBy(() -> service.create(ania,
                    new CreateConversationRequest(ConversationKind.DIRECT, List.of(2), null, null)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("a group starts with its creator as admin and the chosen settings, defaulting to joiners-only history")
        void group() {
            when(boards.shareABoard(any(), any())).thenReturn(true);

            service.create(ania, new CreateConversationRequest(ConversationKind.GROUP, List.of(2, 3), null, null));

            ArgumentCaptor<Conversation> conversation = ArgumentCaptor.forClass(Conversation.class);
            verify(conversations).save(conversation.capture());
            assertThat(conversation.getValue().getHistoryVisibility()).isEqualTo(HistoryVisibility.JOINED);
            assertThat(conversation.getValue().getWhoCanAdd()).isEqualTo(WhoCanAdd.MEMBERS);
            assertThat(conversation.getValue().getDirectKey()).isNull();
            ArgumentCaptor<ConversationMember> saved = ArgumentCaptor.forClass(ConversationMember.class);
            verify(members, times(3)).save(saved.capture());
            assertThat(saved.getAllValues()).extracting(ConversationMember::getRole)
                    .containsExactly(MemberRole.ADMIN, MemberRole.MEMBER, MemberRole.MEMBER);
            verify(notifier).notify(anyList(), eq(new ConversationEvent(ConversationEventType.MEMBERS, null, null, 1)));
        }
    }

    @Nested
    class Membership {

        private Conversation group;
        private ConversationMember admin;
        private ConversationMember other;

        @BeforeEach
        void group() {
            group = conversation(5, ConversationKind.GROUP, ania);
            admin = joined(1, group, ania, MemberRole.ADMIN, MemberState.ACTIVE);
            other = joined(2, group, bartek, MemberRole.MEMBER, MemberState.ACTIVE);
            inConversation(group, admin, other);
            callerIs(admin);
            callerIs(other);
            when(locks.lock(5L)).thenReturn(group);
        }

        @Test
        @DisplayName("an admin adds people, who join from the next message on, and the group waits for a commit")
        void adminAdds() {
            when(boards.shareABoard(any(), any())).thenReturn(true);

            service.addMembers(ania, 5L, new AddMembersRequest(List.of(2, 3)));

            ArgumentCaptor<ConversationMember> saved = ArgumentCaptor.forClass(ConversationMember.class);
            verify(members).save(saved.capture());
            assertThat(saved.getValue().getUser()).isEqualTo(celina);
            assertThat(saved.getValue().getJoinedSeq()).isEqualTo(group.getNextSeq());
            assertThat(group.isNeedsCommit()).isTrue();
        }

        @Test
        @DisplayName("a member adds people only when the group lets members add")
        void memberAdds() {
            assertThatThrownBy(() -> service.addMembers(bartek, 5L, new AddMembersRequest(List.of(3))))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.NOT_CONVERSATION_ADMIN);

            group.configure(null, WhoCanAdd.MEMBERS);
            when(boards.shareABoard(any(), any())).thenReturn(true);
            service.addMembers(bartek, 5L, new AddMembersRequest(List.of(3)));

            verify(members).save(any());
        }

        @Test
        @DisplayName("nobody is added to a direct conversation")
        void notToADirectConversation() {
            Conversation direct = conversation(6, ConversationKind.DIRECT, ania);
            callerIs(joined(9, direct, ania, MemberRole.ADMIN, MemberState.ACTIVE));

            assertThatThrownBy(() -> service.addMembers(ania, 6L, new AddMembersRequest(List.of(3))))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
        }

        @Test
        @DisplayName("an admin removes someone, who is told along with everyone else")
        void adminRemoves() {
            service.removeMember(ania, 5L, 2);

            verify(members).deleteMembership(2L);
            assertThat(group.isNeedsCommit()).isTrue();
            verify(restarts).restartOrphans(List.of(5L));
            verify(notifier).notify(List.of(ania, bartek), new ConversationEvent(ConversationEventType.MEMBERS, 5L, null, 1));
        }

        @Test
        @DisplayName("a member cannot remove anyone, and nobody can remove a stranger to the group")
        void removalRules() {
            assertThatThrownBy(() -> service.removeMember(bartek, 5L, 1))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.NOT_CONVERSATION_ADMIN);
            assertThatThrownBy(() -> service.removeMember(ania, 5L, 3))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("the last admin to leave hands the role to the longest-standing active member")
        void lastAdminLeaves() {
            service.removeMember(ania, 5L, 1);

            verify(members).deleteMembership(1L);
            assertThat(other.getRole()).isEqualTo(MemberRole.ADMIN);
            assertThat(group.isNeedsCommit()).isTrue();
        }

        @Test
        @DisplayName("the last person to leave takes the group with them")
        void lastPersonLeaves() {
            inConversation(group, admin);

            service.removeMember(ania, 5L, 1);

            verify(conversations).deleteWithContents(5L);
            verify(members, never()).deleteMembership(any());
        }

        @Test
        @DisplayName("the last admin cannot step down, another admin can be named")
        void roles() {
            assertThatThrownBy(() -> service.setRole(ania, 5L, 1, new MemberRoleRequest(MemberRole.MEMBER)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);

            service.setRole(ania, 5L, 2, new MemberRoleRequest(MemberRole.ADMIN));

            assertThat(other.getRole()).isEqualTo(MemberRole.ADMIN);
        }

        @Test
        @DisplayName("preferences are the caller's own, settings are the admins'")
        void preferencesAndSettings() {
            service.update(bartek, 5L, new UpdateConversationRequest(true, true, null, null, null, null));
            assertThat(other.isMuted()).isTrue();
            assertThat(other.isPinned()).isTrue();
            verify(notifier).notify(List.of(bartek), new ConversationEvent(ConversationEventType.MEMBERS, 5L, null, 2));

            assertThatThrownBy(() -> service.update(bartek, 5L,
                    new UpdateConversationRequest(null, null, null, HistoryVisibility.ALL, null, null)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.NOT_CONVERSATION_ADMIN);

            service.update(ania, 5L, new UpdateConversationRequest(null, null, null, HistoryVisibility.ALL, WhoCanAdd.MEMBERS, null));
            assertThat(group.getHistoryVisibility()).isEqualTo(HistoryVisibility.ALL);
            assertThat(group.getWhoCanAdd()).isEqualTo(WhoCanAdd.MEMBERS);
        }

        @Test
        @DisplayName("an admin sets how long messages live, zero switches it off, and other values are refused")
        void disappearingTimer() {
            assertThat(group.getDisappearAfterSeconds()).isNull();

            service.update(ania, 5L, new UpdateConversationRequest(null, null, null, null, null, 86400));
            assertThat(group.getDisappearAfterSeconds()).isEqualTo(86400);

            assertThatThrownBy(() -> service.update(ania, 5L,
                    new UpdateConversationRequest(null, null, null, null, null, 17)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
            assertThat(group.getDisappearAfterSeconds()).isEqualTo(86400);

            service.update(ania, 5L, new UpdateConversationRequest(null, null, null, null, null, 0));
            assertThat(group.getDisappearAfterSeconds()).isNull();
        }

        @Test
        @DisplayName("a member who is not an admin cannot set the timer of a group")
        void timerIsForAdmins() {
            assertThatThrownBy(() -> service.update(bartek, 5L,
                    new UpdateConversationRequest(null, null, null, null, null, 3600)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.NOT_CONVERSATION_ADMIN);
            assertThat(group.getDisappearAfterSeconds()).isNull();
        }

        @Test
        @DisplayName("either person of a direct chat sets the timer, but nothing else about it")
        void directTimer() {
            Conversation direct = conversation(8, ConversationKind.DIRECT, ania);
            ConversationMember mine = joined(11, direct, bartek, MemberRole.MEMBER, MemberState.ACTIVE);
            ConversationMember theirs = joined(12, direct, ania, MemberRole.MEMBER, MemberState.ACTIVE);
            inConversation(direct, mine, theirs);
            callerIs(mine);
            when(locks.lock(8L)).thenReturn(direct);

            service.update(bartek, 8L, new UpdateConversationRequest(null, null, null, null, null, 3600));
            assertThat(direct.getDisappearAfterSeconds()).isEqualTo(3600);

            assertThatThrownBy(() -> service.update(bartek, 8L,
                    new UpdateConversationRequest(null, null, null, HistoryVisibility.ALL, null, null)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
        }

        @Test
        @DisplayName("typing is told to everyone else, never to the typist")
        void typing() {
            service.typing(ania, 5L);

            verify(notifier).notify(List.of(bartek), new ConversationEvent(ConversationEventType.TYPING, 5L, null, 1));
        }

        @Test
        @DisplayName("the read position only moves forward, and never past the last message")
        void reading() {
            group.take(MlsMessageKind.COMMIT, NOW);
            group.take(MlsMessageKind.APPLICATION, NOW);

            assertThatThrownBy(() -> service.markRead(bartek, 5L, new ReadRequest(3L)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
            service.markRead(bartek, 5L, new ReadRequest(2L));
            service.markRead(bartek, 5L, new ReadRequest(1L));

            assertThat(other.getReadSeq()).isEqualTo(2L);
            assertThat(other.getDeliveredSeq()).isEqualTo(2L);
            verify(notifier).notify(List.of(ania, bartek), new ConversationEvent(ConversationEventType.READ, 5L, 2L, 2));
        }

        @Test
        @DisplayName("delivery only moves forward, never past the last message, tells everyone, and does not count as read")
        void delivering() {
            group.take(MlsMessageKind.COMMIT, NOW);
            group.take(MlsMessageKind.APPLICATION, NOW);

            assertThatThrownBy(() -> service.markDelivered(bartek, 5L, new ReadRequest(3L)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
            service.markDelivered(bartek, 5L, new ReadRequest(2L));
            service.markDelivered(bartek, 5L, new ReadRequest(1L));

            assertThat(other.getDeliveredSeq()).isEqualTo(2L);
            assertThat(other.getReadSeq()).isZero();
            verify(notifier).notify(List.of(ania, bartek),
                    new ConversationEvent(ConversationEventType.DELIVERED, 5L, 2L, 2));
        }
    }

    @Nested
    class Requests {

        private Conversation direct;
        private ConversationMember asker;
        private ConversationMember asked;

        @BeforeEach
        void direct() {
            direct = conversation(7, ConversationKind.DIRECT, ania);
            asker = joined(1, direct, ania, MemberRole.ADMIN, MemberState.ACTIVE);
            asked = joined(2, direct, bartek, MemberRole.ADMIN, MemberState.REQUEST);
            inConversation(direct, asker, asked);
            callerIs(asker);
            callerIs(asked);
        }

        @Test
        @DisplayName("accepting a request makes the conversation active")
        void accept() {
            service.accept(bartek, 7L);

            assertThat(asked.getState()).isEqualTo(MemberState.ACTIVE);
        }

        @Test
        @DisplayName("declining a request removes the direct conversation for both")
        void decline() {
            service.removeMember(bartek, 7L, 2);

            verify(conversations).deleteWithContents(7L);
        }

        @Test
        @DisplayName("an accepted direct conversation is archived or blocked, not left")
        void cannotLeaveAnActiveDirectConversation() {
            assertThatThrownBy(() -> service.removeMember(ania, 7L, 1))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
        }

        @Test
        @DisplayName("blocking someone removes your direct conversation with them, and a block can be lifted")
        void block() {
            when(conversations.findByDirectKey("1:2")).thenReturn(Optional.of(direct));

            service.block(bartek, new BlockRequest(1));
            service.unblock(bartek, 1);

            verify(blocks).save(any(UserBlock.class));
            verify(conversations).deleteWithContents(7L);
            verify(blocks).unblock(bartek, 1);
        }

        @Test
        @DisplayName("nobody blocks themselves")
        void blockSelf() {
            assertThatThrownBy(() -> service.block(ania, new BlockRequest(1)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
        }

        @Test
        @DisplayName("a deleted account takes its direct conversations, leaves its groups and hands on the admin role")
        void forgetAccount() {
            var direct = row(1L, 7L, ConversationKind.DIRECT, MemberRole.ADMIN, MemberState.ACTIVE, 0);
            var inGroup = row(3L, 8L, ConversationKind.GROUP, MemberRole.ADMIN, MemberState.ACTIVE, 0);
            var invited = row(4L, 8L, ConversationKind.GROUP, MemberRole.MEMBER, MemberState.REQUEST, 1);
            var active = row(5L, 8L, ConversationKind.GROUP, MemberRole.MEMBER, MemberState.ACTIVE, 2);
            var alone = row(6L, 9L, ConversationKind.GROUP, MemberRole.ADMIN, MemberState.ACTIVE, 0);
            when(members.membershipsOf(ania)).thenReturn(List.of(direct, inGroup, alone));
            when(members.membershipsIn(7L)).thenReturn(List.of(direct));
            when(members.membershipsIn(8L)).thenReturn(List.of(inGroup, invited, active));
            when(members.membershipsIn(9L)).thenReturn(List.of(alone));
            when(members.usersIn(8L)).thenReturn(List.of(ania, bartek, celina));

            service.forgetAccount(ania);

            verify(conversations).forgetCreator(ania);
            verify(conversations).deleteWithContents(7L);
            verify(conversations).deleteWithContents(9L);
            verify(members).deleteMembership(3L);
            verify(members).setRole(5L, MemberRole.ADMIN);
            verify(conversations).requireCommit(8L);
            verify(restarts).restartOrphans(List.of(8L));
            verify(notifier).notify(List.of(bartek, celina), new ConversationEvent(ConversationEventType.MEMBERS, 8L, null, 1));
        }

        private ConversationMemberRepository.MembershipRow row(Long memberId, Long conversationId, ConversationKind kind,
                                                               MemberRole role, MemberState state, int joinedAfter) {
            var row = mock(ConversationMemberRepository.MembershipRow.class);
            when(row.getMemberId()).thenReturn(memberId);
            when(row.getConversationId()).thenReturn(conversationId);
            when(row.getKind()).thenReturn(kind);
            when(row.getRole()).thenReturn(role);
            when(row.getState()).thenReturn(state);
            when(row.getJoinedAt()).thenReturn(NOW.plusSeconds(joinedAfter));
            return row;
        }
    }

    @Nested
    class Finding {

        @Test
        @DisplayName("contacts are the people you share a board with, minus blocks either way, sorted by name")
        void contacts() {
            User darek = user(4, "darek");
            when(boards.peersOf(ania)).thenReturn(List.of(celina, ania, darek, bartek));
            when(blocks.blockedBy(ania)).thenReturn(Set.of(4));
            when(blocks.blockersOf(ania)).thenReturn(Set.of());
            ready(bartek);

            assertThat(service.contacts(ania)).extracting(ContactDto::name, ContactDto::messaging)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("Bartek", true),
                            org.assertj.core.groups.Tuple.tuple("Celina", false));
        }

        @Test
        @DisplayName("an exact email finds someone with messages, and nothing else does")
        void lookup() {
            when(users.findByEmail("bartek@example.com")).thenReturn(Optional.of(bartek));
            when(users.findByEmail("ania@example.com")).thenReturn(Optional.of(ania));

            assertThat(service.lookup(ania, "bartek@example.com").id()).isEqualTo(2);
            assertThatThrownBy(() -> service.lookup(ania, "ania@example.com"))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.USER_NOT_FOUND);
            assertThatThrownBy(() -> service.lookup(ania, "nobody@example.com"))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.USER_NOT_FOUND);
            assertThatThrownBy(() -> service.lookup(ania, " "))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
            verify(rateLimiter, times(3)).tryConsume(any(), any(), eq("messaging-reach:1"));
        }

        @Test
        @DisplayName("the list comes newest first with each conversation's unread count")
        void list() {
            Conversation older = conversation(10, ConversationKind.DIRECT, ania);
            Conversation newer = conversation(11, ConversationKind.GROUP, ania);
            newer.take(MlsMessageKind.APPLICATION, NOW.plusSeconds(60));
            ConversationMember inOlder = joined(1, older, ania, MemberRole.ADMIN, MemberState.ACTIVE);
            ConversationMember inNewer = joined(2, newer, ania, MemberRole.ADMIN, MemberState.ACTIVE);
            when(members.findAllOf(ania)).thenReturn(List.of(inOlder, inNewer));
            when(members.findAllIn(anyList())).thenReturn(List.of(inOlder, inNewer));
            MlsMessageRepository.UnreadCount count = mock(MlsMessageRepository.UnreadCount.class);
            when(count.getConversationId()).thenReturn(11L);
            when(count.getUnread()).thenReturn(4L);
            when(messages.countUnread(ania, MlsMessageKind.APPLICATION)).thenReturn(List.of(count));

            assertThat(service.list(ania)).extracting(ConversationDto::id, ConversationDto::unread)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(11L, 4L),
                            org.assertj.core.groups.Tuple.tuple(10L, 0L));
            assertThat(service.blocks(ania)).isEmpty();
        }

        @Test
        @DisplayName("an empty list asks nothing more of the database")
        void emptyList() {
            assertThat(service.list(ania)).isEmpty();
            verify(messages, never()).countUnread(any(), any());
        }
    }

    @Test
    @DisplayName("an unknown account is not found")
    void unknownAccount() {
        assertThatThrownBy(() -> service.block(ania, new BlockRequest(99)))
                .isInstanceOf(GlobalException.class)
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.USER_NOT_FOUND);
    }
}
