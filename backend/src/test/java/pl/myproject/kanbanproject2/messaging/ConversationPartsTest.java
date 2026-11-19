package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.user.AccountDeleting;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.NOW;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.conversation;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.device;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.member;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.user;

class ConversationPartsTest {

    private final User ania = user(1, "Ania");
    private final User bartek = user(2, "Bartek");

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("notices go out at once outside a transaction, and survive a broker that is down")
    void notifierOutsideATransaction() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        ConversationEvent event = new ConversationEvent(ConversationEventType.MESSAGE, 5L, 3L, 1);
        doThrow(new IllegalStateException("broker down")).when(template).convertAndSendToUser(eq("bartek@example.com"), any(), any());

        new ConversationNotifier(template).notify(java.util.Arrays.asList(ania, null, bartek), event);

        verify(template).convertAndSendToUser("ania@example.com", ConversationNotifier.QUEUE, event);
    }

    @Test
    @DisplayName("inside a transaction, notices wait for the commit and go out once each")
    void notifierWaitsForTheCommit() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        ConversationNotifier notifier = new ConversationNotifier(template);
        ConversationEvent event = new ConversationEvent(ConversationEventType.COMMIT, 5L, 3L, 1);
        TransactionSynchronizationManager.initSynchronization();

        notifier.notify(List.of(ania), event);
        notifier.notify(List.of(ania), event);
        verify(template, never()).convertAndSendToUser(any(), any(), any());
        List<TransactionSynchronization> pending = TransactionSynchronizationManager.getSynchronizations();
        pending.forEach(TransactionSynchronization::afterCommit);
        pending.forEach(each -> each.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(template).convertAndSendToUser("ania@example.com", ConversationNotifier.QUEUE, event);
    }

    @Test
    @DisplayName("the key directory chains each entry to the last, asks for commits and tells the people affected")
    void keyDirectory() {
        KeyLogRepository log = mock(KeyLogRepository.class);
        MlsWelcomeRepository welcomes = mock(MlsWelcomeRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        ConversationMemberRepository members = mock(ConversationMemberRepository.class);
        ConversationNotifier notifier = mock(ConversationNotifier.class);
        GroupRestarts restarts = mock(GroupRestarts.class);
        KeyDirectory directory = new KeyDirectory(log, mock(IdentityKeyRepository.class), welcomes, conversations,
                members, notifier, restarts);
        byte[] previous = new byte[32];
        when(log.findFirstByUserOrderByIdDesc(ania)).thenReturn(Optional.of(
                new KeyLogEntry(ania, KeyChange.IDENTITY, new byte[32], null, previous, NOW)));
        when(conversations.requireCommitFor(ania)).thenReturn(2);
        when(members.findCompanionsOf(ania)).thenReturn(List.of(ania, bartek));
        CryptoDevice revoked = device(10, ania);

        directory.devicesRevoked(List.of(revoked), NOW);
        directory.devicesRevoked(List.of(), NOW);

        verify(welcomes).deleteAllFor(List.of(revoked));
        verify(log).save(org.mockito.ArgumentMatchers.argThat(entry -> entry.getChange() == KeyChange.DEVICE_REMOVED
                && java.util.Arrays.equals(entry.getPrevHash(), previous)
                && java.util.Arrays.equals(entry.getEntryHash(),
                KeyDirectory.entryHash(previous, KeyChange.DEVICE_REMOVED, revoked.getSignatureKey()))));
        verify(notifier).notify(List.of(ania, bartek), new ConversationEvent(ConversationEventType.MEMBERS, null, null, 1));
        verify(restarts).restartOrphans(List.of());
    }

    @Test
    @DisplayName("a key change in nobody's conversations tells nobody")
    void keyDirectoryAlone() {
        ConversationNotifier notifier = mock(ConversationNotifier.class);
        KeyLogRepository log = mock(KeyLogRepository.class);
        when(log.findFirstByUserOrderByIdDesc(any())).thenReturn(Optional.empty());
        KeyDirectory directory = new KeyDirectory(log, mock(IdentityKeyRepository.class), mock(MlsWelcomeRepository.class),
                mock(ConversationRepository.class), mock(ConversationMemberRepository.class), notifier,
                mock(GroupRestarts.class));

        directory.identityChanged(ania, new byte[32], NOW);
        directory.deviceAdded(device(10, ania), NOW);

        verify(log, org.mockito.Mockito.times(2)).save(any());
        verify(notifier, never()).notify(any(), any());
    }

    @Test
    @DisplayName("the nightly prune drops what has expired")
    void prune() {
        MlsMessageRepository messages = mock(MlsMessageRepository.class);
        when(messages.deleteExpired(NOW)).thenReturn(3, 0);
        MlsMessagePrune prune = new MlsMessagePrune(messages, Clock.fixed(NOW, ZoneOffset.UTC));

        prune.prune();
        prune.prune();

        verify(messages, org.mockito.Mockito.times(2)).deleteExpired(NOW);
    }

    @Test
    @DisplayName("deleting an account hands its conversations to the conversation service")
    void accountDeleting() {
        ConversationService conversations = mock(ConversationService.class);

        new ConversationAccounts(conversations).on(new AccountDeleting(ania));

        verify(conversations).forgetAccount(ania);
    }

    @Test
    @DisplayName("access answers 404 for a conversation the caller is not in and for a device that is not theirs")
    void access() {
        ConversationMemberRepository members = mock(ConversationMemberRepository.class);
        CryptoDeviceRepository devices = mock(CryptoDeviceRepository.class);
        Conversation group = conversation(5, ConversationKind.GROUP, ania);
        ConversationMember mine = member(1, group, ania, MemberRole.ADMIN, MemberState.ACTIVE, NOW);
        when(members.findMembership(5L, 1)).thenReturn(Optional.of(mine));
        ConversationAccess access = new ConversationAccess(new MessagingProperties(true, ""), members, devices);

        assertThat(access.requireMember(ania, 5L)).isSameAs(mine);
        assertThatThrownBy(() -> access.requireMember(bartek, 5L))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.CONVERSATION_NOT_FOUND);
        assertThatThrownBy(() -> access.requireOwnDevice(bartek, 10L))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.CRYPTO_DEVICE_NOT_FOUND);
        assertThatThrownBy(() -> new ConversationAccess(new MessagingProperties(false, ""), members, devices)
                .requireMember(ania, 5L))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.MESSAGING_UNAVAILABLE);
    }

    @Test
    @DisplayName("a one-time key package is spent when claimed, the last-resort one is handed out and kept")
    void claimer() {
        KeyPackageRepository keyPackages = mock(KeyPackageRepository.class);
        CryptoDevice owner = device(10, ania);
        KeyPackage oneTime = new KeyPackage(owner, new byte[]{1}, new byte[]{2}, false, NOW);
        KeyPackage lastResort = new KeyPackage(owner, new byte[]{3}, new byte[]{4}, true, NOW);
        when(keyPackages.lockOneTime(10L)).thenReturn(Optional.of(oneTime), Optional.empty());
        when(keyPackages.findLastResort(10L)).thenReturn(Optional.of(lastResort));
        KeyPackageClaimer claimer = new KeyPackageClaimer(keyPackages);

        assertThat(claimer.claim(10L)).contains(new byte[]{2});
        assertThat(claimer.claim(10L)).contains(new byte[]{4});

        verify(keyPackages).delete(oneTime);
        verify(keyPackages, never()).delete(lastResort);
    }

    @Test
    @DisplayName("a commit moves the epoch and clears a pending change; a message only moves the activity time")
    void conversationCounters() {
        Conversation group = conversation(5, ConversationKind.GROUP, ania);
        group.requireCommit();

        assertThat(group.take(MlsMessageKind.COMMIT, NOW.plusSeconds(5))).isEqualTo(1L);
        assertThat(group.getEpoch()).isEqualTo(1L);
        assertThat(group.isNeedsCommit()).isFalse();
        assertThat(group.getLastActivityAt()).isEqualTo(NOW);
        assertThat(group.take(MlsMessageKind.APPLICATION, NOW.plusSeconds(9))).isEqualTo(2L);
        assertThat(group.getEpoch()).isEqualTo(1L);
        assertThat(group.getLastActivityAt()).isEqualTo(NOW.plusSeconds(9));
        assertThat(group.getLastSeq()).isEqualTo(2L);
    }
}
