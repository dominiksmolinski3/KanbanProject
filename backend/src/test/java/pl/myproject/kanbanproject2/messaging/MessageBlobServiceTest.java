package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.config.BlobStorageProperties;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.storage.BlobStore;
import pl.myproject.kanbanproject2.storage.BlobStoreException;
import pl.myproject.kanbanproject2.user.User;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.NOW;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.conversation;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.member;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.user;

class MessageBlobServiceTest {

    private final User ania = user(1, "Ania");

    private ConversationAccess access;
    private MessageBlobRepository blobs;
    private BlobStore store;
    private BoardService boards;
    private Conversation group;
    private ConversationMember me;
    private MessageBlobService service;

    private static BlobStorageProperties properties(long quota) {
        return new BlobStorageProperties("", "", "task-attachments", "", 8, 1, 500, quota);
    }

    @BeforeEach
    void setUp() {
        access = mock(ConversationAccess.class);
        blobs = mock(MessageBlobRepository.class);
        store = mock(BlobStore.class);
        boards = mock(BoardService.class);
        group = conversation(5, ConversationKind.GROUP, ania);
        me = member(1, group, ania, MemberRole.ADMIN, MemberState.ACTIVE, NOW);
        when(access.requireMember(ania, 5L)).thenReturn(me);
        when(store.isConfigured()).thenReturn(true);
        doAnswer(call -> {
            MessageBlob saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 77L);
            return saved;
        }).when(blobs).save(any());
        service = newService(1024L * 1024 * 1024);
    }

    private MessageBlobService newService(long quota) {
        return new MessageBlobService(access, blobs, store, boards, TransactionOperations.withoutTransaction(),
                properties(quota), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MockMultipartFile file(int size) {
        return new MockMultipartFile("file", "attachment", "application/octet-stream", new byte[size]);
    }

    @Test
    @DisplayName("an upload is stored as opaque bytes under the conversation, with the conversation's expiry")
    void upload() {
        group.disappearAfter(3600);

        MessageBlobDto stored = service.upload(ania, 5L, file(100));

        assertThat(stored).isEqualTo(new MessageBlobDto(77L, 100));
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(store).put(name.capture(), eq("application/octet-stream"), any(), eq(100L));
        assertThat(name.getValue()).startsWith("messages/5/");
        ArgumentCaptor<MessageBlob> saved = ArgumentCaptor.forClass(MessageBlob.class);
        verify(blobs).save(saved.capture());
        assertThat(saved.getValue().getBlobName()).isEqualTo(name.getValue());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    @DisplayName("without a timer an attachment is kept as long as the messages are")
    void retention() {
        service.upload(ania, 5L, file(10));

        ArgumentCaptor<MessageBlob> saved = ArgumentCaptor.forClass(MessageBlob.class);
        verify(blobs).save(saved.capture());
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(MessageDeliveryService.RETENTION));
    }

    @Test
    @DisplayName("a viewer of a board channel cannot upload, and nothing reaches the store")
    void viewerRefused() {
        Conversation channel = conversation(6, ConversationKind.BOARD, null);
        ReflectionTestUtils.setField(channel, "boardId", 4);
        ConversationMember viewer = member(2, channel, ania, MemberRole.VIEWER, MemberState.ACTIVE, NOW);
        when(access.requireMember(ania, 6L)).thenReturn(viewer);
        when(boards.requireWritable(ania, 4)).thenThrow(new GlobalException(ExceptionIdentifier.VIEWER_READ_ONLY));

        assertThatThrownBy(() -> service.upload(ania, 6L, file(10)))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.VIEWER_READ_ONLY);

        verify(store, never()).put(anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("an editor of a board channel uploads once the board agrees")
    void boardEditor() {
        Conversation channel = conversation(6, ConversationKind.BOARD, null);
        ReflectionTestUtils.setField(channel, "boardId", 4);
        when(access.requireMember(ania, 6L)).thenReturn(member(2, channel, ania, MemberRole.MEMBER, MemberState.ACTIVE, NOW));

        service.upload(ania, 6L, file(10));

        verify(boards).requireWritable(ania, 4);
        verify(store).put(anyString(), anyString(), any(), eq(10L));
    }

    @Test
    @DisplayName("an upload is refused when the store is not configured, when it is empty and when it is too big")
    void refusals() {
        when(store.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service.upload(ania, 5L, file(10)))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE);

        when(store.isConfigured()).thenReturn(true);
        assertThatThrownBy(() -> service.upload(ania, 5L, file(0)))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_ATTACHMENT);
        assertThatThrownBy(() -> service.upload(ania, 5L, file((int) MessageBlobService.MAX_BLOB_BYTES + 1)))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.ATTACHMENT_TOO_LARGE);

        verify(store, never()).put(anyString(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("a conversation that used up its space cannot take another file")
    void quota() {
        when(blobs.totalSize(5L)).thenReturn(95L);

        assertThatThrownBy(() -> newService(100).upload(ania, 5L, file(10)))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.ATTACHMENT_QUOTA_EXCEEDED);
        newService(105).upload(ania, 5L, file(10));
    }

    @Test
    @DisplayName("a store that fails reports the failure and saves no row")
    void storeFails() {
        doThrow(new BlobStoreException("down", null)).when(store).put(anyString(), anyString(), any(), anyLong());

        assertThatThrownBy(() -> service.upload(ania, 5L, file(10)))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.FILE_UPLOAD_FAILED);

        verify(blobs, never()).save(any());
    }

    @Test
    @DisplayName("an unreadable upload reports the failure and saves no row")
    void unreadableUpload() throws IOException {
        MockMultipartFile broken = mock(MockMultipartFile.class);
        when(broken.isEmpty()).thenReturn(false);
        when(broken.getSize()).thenReturn(10L);
        when(broken.getInputStream()).thenThrow(new IOException("broken"));

        assertThatThrownBy(() -> service.upload(ania, 5L, broken))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.FILE_UPLOAD_FAILED);

        verify(blobs, never()).save(any());
    }

    @Test
    @DisplayName("when the row cannot be saved the stored bytes are removed again")
    void rowFails() {
        doThrow(new IllegalStateException("db")).when(blobs).save(any());

        assertThatThrownBy(() -> service.upload(ania, 5L, file(10))).isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(store).put(name.capture(), anyString(), any(), anyLong());
        verify(store).remove(name.getValue());
    }

    @Test
    @DisplayName("a failure to clean up does not hide the original error")
    void cleanupFails() {
        doThrow(new IllegalStateException("db")).when(blobs).save(any());
        doThrow(new BlobStoreException("down", null)).when(store).remove(anyString());

        assertThatThrownBy(() -> service.upload(ania, 5L, file(10))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a member reads a blob of their conversation")
    void download() {
        MessageBlob blob = new MessageBlob(group, ania, "messages/5/x", 3, NOW, NOW.plus(Duration.ofDays(1)));
        when(blobs.findInConversation(7L, 5L)).thenReturn(Optional.of(blob));
        when(store.read("messages/5/x")).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));

        MessageBlobContent content = service.content(ania, 5L, 7L);

        assertThat(content.size()).isEqualTo(3);
        assertThat(content.stream()).hasBinaryContent(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("a blob of another conversation answers like one that does not exist")
    void foreignBlob() {
        when(blobs.findInConversation(7L, 5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.content(ania, 5L, 7L))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.ATTACHMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("a stranger to the conversation cannot read, and a store that fails is reported")
    void downloadRefusals() {
        User outsider = user(9, "Obcy");
        when(access.requireMember(outsider, 5L)).thenThrow(ConversationAccess.conversationNotFound());
        assertThatThrownBy(() -> service.content(outsider, 5L, 7L))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.CONVERSATION_NOT_FOUND);

        MessageBlob blob = new MessageBlob(group, ania, "messages/5/x", 3, NOW, NOW);
        when(blobs.findInConversation(7L, 5L)).thenReturn(Optional.of(blob));
        when(store.read("messages/5/x")).thenThrow(new BlobStoreException("gone", null));
        assertThatThrownBy(() -> service.content(ania, 5L, 7L))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.FILE_UPLOAD_FAILED);
    }

    @Test
    @DisplayName("expired blobs are removed from the store and then from the table, a batch at a time")
    void prune() {
        MessageBlob first = new MessageBlob(group, ania, "messages/5/a", 1, NOW, NOW);
        MessageBlob second = new MessageBlob(group, ania, "messages/5/b", 1, NOW, NOW);
        when(blobs.findExpired(any(), any(Pageable.class))).thenReturn(List.of(first, second)).thenReturn(List.of());
        MessageBlobPrune prune = new MessageBlobPrune(blobs, service, Clock.fixed(NOW, ZoneOffset.UTC));

        prune.prune();

        verify(store).remove("messages/5/a");
        verify(store).remove("messages/5/b");
        verify(blobs).deleteAll(List.of(first, second));
    }

    @Test
    @DisplayName("pruning with nothing expired touches nothing")
    void pruneNothing() {
        when(blobs.findExpired(any(), any(Pageable.class))).thenReturn(List.of());

        new MessageBlobPrune(blobs, service, Clock.fixed(NOW, ZoneOffset.UTC)).prune();

        verify(store, never()).remove(anyString());
        verify(blobs, never()).deleteAll(any());
    }

    @Test
    @DisplayName("the sweep keeps the blobs that have a row and reports the prefix it owns")
    void owner() {
        MessageBlobs owner = new MessageBlobs(blobs);
        when(blobs.findBlobNamesIn(List.of("messages/5/a", "messages/5/b"))).thenReturn(List.of("messages/5/a"));

        assertThat(owner.prefix()).isEqualTo("messages/");
        assertThat(owner.referenced(List.of("messages/5/a", "messages/5/b"))).containsExactly("messages/5/a");
    }
}
