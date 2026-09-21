package pl.myproject.kanbanproject2.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHistoryServiceTest {

    private static final Clock FIXED = Clock.fixed(
            Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);

    private ChatRepository chatRepository;
    private BoardService boardService;
    private ChatHistoryService service;
    private Board board;
    private User anna;
    private User bob;

    @BeforeEach
    void setUp() {
        chatRepository = mock(ChatRepository.class);
        boardService = mock(BoardService.class);
        service = new ChatHistoryService(chatRepository, new ChatMessageMapper(), boardService, 90, FIXED);

        anna = user(1, "anna@example.com");
        bob = user(2, "bob@example.com");
        board = new Board();
        board.setId(7);
        when(boardService.resolve(any(), any())).thenReturn(board);
    }

    private static User user(int id, String email) {
        var user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    private Chat stored(int id, String content) {
        var chat = Chat.builder()
                .id(id)
                .type(MessageType.CHAT)
                .content(content)
                .sender("anna@example.com")
                .board(board)
                .timestamp(LocalDateTime.of(2026, 9, 20, 9, 0))
                .build();
        return chat;
    }

    private void boardPageHolds(List<Chat> rows, long total) {
        when(chatRepository.findByBoardOrderByTimestampDescIdDesc(eq(board), any(Pageable.class)))
                .thenReturn(new PageImpl<>(rows, PageRequest.of(0, 25), total));
    }

    @Nested
    @DisplayName("a board's conversation")
    class BoardHistory {

        @Test
        @DisplayName("comes back mapped, with the board on each entry")
        void mapsTheRows() {
            boardPageHolds(List.of(stored(1, "hello")), 1);

            var results = service.boardHistory(anna, 7, null, null);

            assertThat(results.messages()).singleElement().satisfies(entry -> {
                assertThat(entry.content()).isEqualTo("hello");
                assertThat(entry.boardId()).isEqualTo(7);
                assertThat(entry.sender()).isEqualTo("anna@example.com");
                assertThat(entry.recipientId()).isNull();
            });
            assertThat(results.totalEntries()).isEqualTo(1);
        }

        @Test
        @DisplayName("defaults to the first page of 25")
        void defaultsToTwentyFive() {
            boardPageHolds(List.of(), 0);

            var results = service.boardHistory(anna, 7, null, null);

            var captor = forClass(Pageable.class);
            verify(chatRepository).findByBoardOrderByTimestampDescIdDesc(eq(board), captor.capture());
            assertThat(captor.getValue().getPageNumber()).isZero();
            assertThat(captor.getValue().getPageSize()).isEqualTo(25);
            assertThat(results.size()).isEqualTo(25);
        }

        @Test
        @DisplayName("asks BoardService which board, so a board the caller cannot see is its 404")
        void boardScopingIsBoardServices() {
            when(boardService.resolve(anna, 99))
                    .thenThrow(new GlobalException(ExceptionIdentifier.BOARD_NOT_FOUND));

            assertThatThrownBy(() -> service.boardHistory(anna, 99, null, null))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.BOARD_NOT_FOUND);
        }

        @Test
        @DisplayName("a size over the ceiling is a 400 rather than a silent 100")
        void sizeOverTheCeilingIsRefused() {
            assertThatThrownBy(() -> service.boardHistory(anna, 7, 0, 101))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_CHAT_REQUEST);
        }

        @Test
        @DisplayName("the ceiling itself is served")
        void theCeilingIsServed() {
            boardPageHolds(List.of(), 0);

            assertThat(service.boardHistory(anna, 7, 0, 100).size()).isEqualTo(100);
        }

        @Test
        @DisplayName("a size below one and a negative page are both refused")
        void nonsensePagingIsRefused() {
            assertThatThrownBy(() -> service.boardHistory(anna, 7, 0, 0))
                    .isInstanceOf(GlobalException.class);
            assertThatThrownBy(() -> service.boardHistory(anna, 7, -1, null))
                    .isInstanceOf(GlobalException.class);
        }
    }

    @Nested
    @DisplayName("a direct thread")
    class DirectHistory {

        @Test
        @DisplayName("reads both directions between the caller and the peer")
        void readsBothDirections() {
            when(boardService.peersOf(anna)).thenReturn(List.of(anna, bob));
            when(chatRepository.findDirectThread(eq("anna@example.com"), eq("bob@example.com"), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

            var results = service.directHistory(anna, "bob@example.com", null, null);

            assertThat(results.messages()).isEmpty();
            verify(chatRepository).findDirectThread(eq("anna@example.com"), eq("bob@example.com"), any());
        }

        @Test
        @DisplayName("an account sharing no board is a 404, the same answer an unknown address gets")
        void strangerIsNotFound() {
            when(boardService.peersOf(anna)).thenReturn(List.of(anna));

            assertThatThrownBy(() -> service.directHistory(anna, "bob@example.com", null, null))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.USER_NOT_FOUND);

            verify(chatRepository, never()).findDirectThread(any(), any(), any());
        }

        @Test
        @DisplayName("naming nobody is a 400, not a 404 - nothing was looked up")
        void noPeerIsABadRequest() {
            assertThatThrownBy(() -> service.directHistory(anna, "  ", null, null))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_CHAT_REQUEST);
            assertThatThrownBy(() -> service.directHistory(anna, null, null, null))
                    .isInstanceOf(GlobalException.class);
        }

        @Test
        @DisplayName("the peer is matched however the address was capitalised")
        void peerMatchIsCaseInsensitive() {
            when(boardService.peersOf(anna)).thenReturn(List.of(bob));
            when(chatRepository.findDirectThread(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

            service.directHistory(anna, "BOB@EXAMPLE.COM", null, null);

            verify(chatRepository).findDirectThread(eq("anna@example.com"), eq("bob@example.com"), any());
        }

        @Test
        @DisplayName("a direct message maps with no board and the recipient on it")
        void directEntryHasNoBoard() {
            var direct = Chat.builder()
                    .id(3)
                    .type(MessageType.PRIVATE)
                    .content("psst")
                    .sender("anna@example.com")
                    .recipientId("bob@example.com")
                    .timestamp(LocalDateTime.of(2026, 9, 20, 9, 0))
                    .build();
            when(boardService.peersOf(anna)).thenReturn(List.of(bob));
            when(chatRepository.findDirectThread(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(direct), PageRequest.of(0, 25), 1));

            var entry = service.directHistory(anna, "bob@example.com", null, null).messages().getFirst();

            assertThat(entry.boardId()).isNull();
            assertThat(entry.recipientId()).isEqualTo("bob@example.com");
            assertThat(entry.type()).isEqualTo(MessageType.PRIVATE);
        }
    }

    @Nested
    @DisplayName("the retention sweep")
    class Retention {

        @Test
        @DisplayName("removes everything older than the window, measured on the supplied clock")
        void removesOlderThanTheWindow() {
            when(chatRepository.deleteOlderThan(any())).thenReturn(4);

            service.pruneExpiredMessages();

            verify(chatRepository).deleteOlderThan(
                    LocalDateTime.ofInstant(FIXED.instant(), ZoneOffset.UTC).minusDays(90));
        }

        @Test
        @DisplayName("a window of zero or less turns the sweep off rather than deleting everything")
        void zeroTurnsItOff() {
            var never = new ChatHistoryService(chatRepository, new ChatMessageMapper(), boardService, 0, FIXED);

            never.pruneExpiredMessages();

            verify(chatRepository, never()).deleteOlderThan(any());
        }

        @Test
        @DisplayName("a sweep that removes nothing is silent")
        void nothingToRemoveIsSilent() {
            when(chatRepository.deleteOlderThan(any())).thenReturn(0);

            service.pruneExpiredMessages();

            verify(chatRepository).deleteOlderThan(any());
        }
    }
}
