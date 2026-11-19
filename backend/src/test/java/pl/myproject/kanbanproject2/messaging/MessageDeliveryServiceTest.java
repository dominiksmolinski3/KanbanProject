package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.NOW;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.conversation;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.device;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.identity;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.member;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.user;

class MessageDeliveryServiceTest {

    private ConversationAccess access;
    private ConversationLocks locks;
    private ConversationMemberRepository members;
    private MlsMessageRepository messages;
    private MlsWelcomeRepository welcomes;
    private CryptoDeviceRepository devices;
    private IdentityKeyRepository identities;
    private KeyLogRepository keyLog;
    private KeyPackageClaimer claimer;
    private ConversationDeviceRepository holders;
    private BoardService boards;
    private ConversationNotifier notifier;
    private MessageDeliveryService service;

    private final User ania = user(1, "Ania");
    private final User bartek = user(2, "Bartek");
    private final User celina = user(3, "Celina");
    private Conversation group;
    private ConversationMember me;
    private CryptoDevice myDevice;

    @BeforeEach
    void setUp() {
        access = mock(ConversationAccess.class);
        locks = mock(ConversationLocks.class);
        members = mock(ConversationMemberRepository.class);
        messages = mock(MlsMessageRepository.class);
        welcomes = mock(MlsWelcomeRepository.class);
        devices = mock(CryptoDeviceRepository.class);
        identities = mock(IdentityKeyRepository.class);
        keyLog = mock(KeyLogRepository.class);
        claimer = mock(KeyPackageClaimer.class);
        holders = mock(ConversationDeviceRepository.class);
        boards = mock(BoardService.class);
        notifier = mock(ConversationNotifier.class);
        service = new MessageDeliveryService(access, mock(ConversationRepository.class), locks, members, messages,
                welcomes, devices, identities, keyLog, claimer, holders, boards, notifier, new MlsMessageMapper(),
                new WelcomeMapper(), new KeyLogEntryMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        group = conversation(5, ConversationKind.GROUP, ania);
        me = member(1, group, ania, MemberRole.ADMIN, MemberState.ACTIVE, NOW);
        ConversationMember other = member(2, group, bartek, MemberRole.MEMBER, MemberState.ACTIVE, NOW);
        myDevice = device(10, ania);
        when(access.requireMember(ania, 5L)).thenReturn(me);
        when(access.requireOwnDevice(ania, 10L)).thenReturn(myDevice);
        when(locks.lock(5L)).thenReturn(group);
        when(members.findAllIn(List.of(group))).thenReturn(List.of(me, other));
    }

    @Test
    @DisplayName("the first commit takes epoch 0, stores a welcome for each new device and tells everyone")
    void firstCommit() {
        CryptoDevice bartekDevice = device(20, bartek);
        when(devices.findLiveByIds(anyCollection())).thenReturn(List.of(bartekDevice));

        PostedDto posted = service.commit(ania, 5L, new CommitRequest(10L, 0L, new byte[]{1}, new byte[]{2}, List.of(20L)));

        assertThat(posted.seq()).isEqualTo(1L);
        assertThat(posted.epoch()).isEqualTo(1L);
        ArgumentCaptor<MlsMessage> stored = ArgumentCaptor.forClass(MlsMessage.class);
        verify(messages).save(stored.capture());
        assertThat(stored.getValue().getKind()).isEqualTo(MlsMessageKind.COMMIT);
        assertThat(stored.getValue().getEpoch()).isZero();
        assertThat(stored.getValue().getExpiresAt()).isEqualTo(NOW.plus(MessageDeliveryService.RETENTION));
        verify(welcomes).saveAll(anyList());
        verify(holders).record(5L, 10L);
        verify(holders).record(5L, 20L);
        verify(notifier).notify(List.of(ania, bartek), new ConversationEvent(ConversationEventType.COMMIT, 5L, 1L, 1));
        verify(notifier).notify(List.of(bartek), new ConversationEvent(ConversationEventType.WELCOME, 5L, 1L, 1));
    }

    @Test
    @DisplayName("a commit for an epoch that has passed is refused")
    void staleCommit() {
        group.take(MlsMessageKind.COMMIT, NOW);

        assertThatThrownBy(() -> service.commit(ania, 5L, new CommitRequest(10L, 0L, new byte[]{1}, null, null)))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.STALE_EPOCH);
        verify(messages, never()).save(any());
    }

    @Test
    @DisplayName("a welcome for a device outside the conversation, or without its body, is refused")
    void welcomeRules() {
        when(devices.findLiveByIds(anyCollection())).thenReturn(List.of(device(30, celina)));

        assertThatThrownBy(() -> service.commit(ania, 5L, new CommitRequest(10L, 0L, new byte[]{1}, new byte[]{2}, List.of(30L))))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.CRYPTO_DEVICE_NOT_FOUND);
        assertThatThrownBy(() -> service.commit(ania, 5L, new CommitRequest(10L, 0L, new byte[]{1}, null, List.of(30L))))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
    }

    @Test
    @DisplayName("a message goes out at the current epoch, accepts a request by replying and counts as read by its sender")
    void post() {
        group.take(MlsMessageKind.COMMIT, NOW);
        ConversationMember requested = member(3, group, celina, MemberRole.MEMBER, MemberState.REQUEST, NOW);
        CryptoDevice celinaDevice = device(30, celina);
        when(access.requireMember(celina, 5L)).thenReturn(requested);
        when(access.requireOwnDevice(celina, 30L)).thenReturn(celinaDevice);

        PostedDto posted = service.post(celina, 5L, new MessageRequest(30L, 1L, new byte[]{9}));

        assertThat(posted.seq()).isEqualTo(2L);
        assertThat(requested.getState()).isEqualTo(MemberState.ACTIVE);
        assertThat(requested.getReadSeq()).isEqualTo(2L);
        assertThat(group.getLastActivityAt()).isEqualTo(NOW);
        verify(notifier).notify(List.of(ania, bartek), new ConversationEvent(ConversationEventType.MESSAGE, 5L, 2L, 3));
    }

    @Test
    @DisplayName("a message waits while the epoch is wrong or a member change still needs a commit")
    void postRefusals() {
        assertThatThrownBy(() -> service.post(ania, 5L, new MessageRequest(10L, 1L, new byte[]{9})))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.STALE_EPOCH);
        group.requireCommit();
        assertThatThrownBy(() -> service.post(ania, 5L, new MessageRequest(10L, 0L, new byte[]{9})))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.STALE_EPOCH);
        assertThatThrownBy(() -> service.post(ania, 5L,
                new MessageRequest(10L, 0L, new byte[MessageDeliveryService.MAX_MESSAGE_BYTES + 1])))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.MESSAGE_TOO_LARGE);
    }

    @Test
    @DisplayName("messages are read from where the member joined, 25 at a time unless asked, never more than 100")
    void paging() {
        ConversationMember late = member(4, group, celina, MemberRole.MEMBER, MemberState.ACTIVE, NOW);
        group.take(MlsMessageKind.COMMIT, NOW);
        when(access.requireMember(celina, 5L)).thenReturn(late);
        MlsMessage stored = new MlsMessage(group, 1, 0, MlsMessageKind.COMMIT, myDevice, new byte[]{1}, NOW, NOW);
        when(messages.findAfter(eq(group), eq(0L), eq(PageRequest.of(0, 25)))).thenReturn(List.of(stored));

        assertThat(service.messages(ania, 5L, null, null)).singleElement()
                .satisfies(message -> {
                    assertThat(message.senderId()).isEqualTo(1);
                    assertThat(message.senderDeviceId()).isEqualTo(10L);
                });
        service.messages(celina, 5L, 0L, 100);
        verify(messages).findAfter(group, 0L, PageRequest.of(0, 100));
        assertThatThrownBy(() -> service.messages(ania, 5L, 0L, 101))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
        assertThatThrownBy(() -> service.messages(ania, 5L, -1L, 10))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CONVERSATION_REQUEST);
    }

    @Test
    @DisplayName("key packages are claimed only for live devices of members, one each")
    void claim() {
        CryptoDevice bartekDevice = device(20, bartek);
        CryptoDevice outsider = device(30, celina);
        CryptoDevice emptied = device(21, bartek);
        when(devices.findLiveByIds(anyCollection())).thenReturn(List.of(bartekDevice, outsider, emptied));
        when(claimer.claim(20L)).thenReturn(Optional.of(new byte[]{7}));
        when(claimer.claim(21L)).thenReturn(Optional.empty());

        assertThat(service.claim(ania, 5L, new ClaimRequest(List.of(20L, 30L, 21L))))
                .extracting(ClaimedKeyPackageDto::deviceId).containsExactly(20L);
        verify(claimer, never()).claim(30L);
    }

    @Test
    @DisplayName("the directory lists every member with their identity and devices, revoked ones marked")
    void directory() {
        CryptoDevice live = device(20, bartek);
        CryptoDevice revoked = device(21, bartek);
        revoked.revokeAt(NOW);
        when(identities.findAllById(anyIterable())).thenReturn(List.of(identity(bartek)));
        when(devices.findAllOf(anyList())).thenReturn(List.of(live, revoked));

        List<DirectoryEntryDto> directory = service.directory(ania, 5L);

        assertThat(directory).extracting(DirectoryEntryDto::userId).containsExactly(1, 2);
        assertThat(directory.get(0).identityKey()).isNull();
        assertThat(directory.get(1).devices()).extracting(DirectoryDeviceDto::revoked).containsExactly(false, true);
    }

    @Test
    @DisplayName("welcomes are a device's own, and dropping one names the device")
    void welcomes() {
        when(welcomes.findFor(myDevice)).thenReturn(List.of(new MlsWelcome(myDevice, group, 3, new byte[]{4}, NOW)));

        assertThat(service.welcomes(ania, 10L)).singleElement()
                .satisfies(welcome -> assertThat(welcome.conversationId()).isEqualTo(5L));
        service.dropWelcome(ania, 10L, 8L);

        verify(welcomes).deleteForDevice(8L, myDevice);
    }

    @Test
    @DisplayName("a key log is visible to its owner, to people sharing a conversation or a board, and to nobody else")
    void keyLog() {
        when(members.shareAConversation(1, 2)).thenReturn(true);
        when(boards.shareABoard(ania, 4)).thenReturn(true);

        service.keyLog(ania, 1, null);
        service.keyLog(ania, 2, 5L);
        service.keyLog(ania, 4, 0L);

        verify(keyLog).findAfter(2, 5L, PageRequest.of(0, MessageDeliveryService.MAX_PAGE));
        assertThatThrownBy(() -> service.keyLog(ania, 9, null))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.USER_NOT_FOUND);
    }
}
