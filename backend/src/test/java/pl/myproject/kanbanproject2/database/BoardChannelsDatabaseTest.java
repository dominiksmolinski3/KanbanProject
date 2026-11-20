package pl.myproject.kanbanproject2.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.board.BoardMapper;
import pl.myproject.kanbanproject2.board.BoardRepository;
import pl.myproject.kanbanproject2.board.BoardRole;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.CreateBoardRequest;
import pl.myproject.kanbanproject2.board.PatchBoardRequest;
import pl.myproject.kanbanproject2.messaging.BoardChannels;
import pl.myproject.kanbanproject2.messaging.ConversationAccess;
import pl.myproject.kanbanproject2.messaging.ConversationAccounts;
import pl.myproject.kanbanproject2.messaging.ConversationDeviceRepository;
import pl.myproject.kanbanproject2.messaging.ConversationLocks;
import pl.myproject.kanbanproject2.messaging.ConversationMapper;
import pl.myproject.kanbanproject2.messaging.ConversationMemberRepository;
import pl.myproject.kanbanproject2.messaging.ConversationNotifier;
import pl.myproject.kanbanproject2.messaging.ConversationRepository;
import pl.myproject.kanbanproject2.messaging.ConversationService;
import pl.myproject.kanbanproject2.messaging.CryptoDeviceRepository;
import pl.myproject.kanbanproject2.messaging.GroupRestarts;
import pl.myproject.kanbanproject2.messaging.IdentityKeyRepository;
import pl.myproject.kanbanproject2.messaging.MessagingProperties;
import pl.myproject.kanbanproject2.messaging.MlsMessageRepository;
import pl.myproject.kanbanproject2.messaging.UserBlockRepository;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserMapper;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig({DatabaseTestConfiguration.class, BoardChannelsDatabaseTest.Beans.class})
@Import({BoardService.class, BoardMapper.class, UserService.class, UserMapper.class})
class BoardChannelsDatabaseTest {

    @Configuration
    static class Beans {
        @Bean
        MessagingProperties messagingProperties() {
            return new MessagingProperties(true, "");
        }

        @Bean
        ConversationNotifier conversationNotifier() {
            return new ConversationNotifier(mock(SimpMessagingTemplate.class));
        }

        @Bean
        ConversationAccess conversationAccess(MessagingProperties properties, ConversationMemberRepository members,
                                              CryptoDeviceRepository devices) {
            return new ConversationAccess(properties, members, devices);
        }

        @Bean
        ConversationLocks conversationLocks() {
            return new ConversationLocks();
        }

        @Bean
        GroupRestarts groupRestarts(ConversationRepository conversations, ConversationDeviceRepository holders,
                                    ConversationMemberRepository members, ConversationLocks locks,
                                    ConversationNotifier notifier) {
            return new GroupRestarts(conversations, holders, members, locks, notifier);
        }

        @Bean
        BoardChannels boardChannels(ConversationRepository conversations, ConversationMemberRepository members,
                                    ConversationLocks locks, ConversationNotifier notifier, GroupRestarts restarts,
                                    BoardService boards) {
            return new BoardChannels(conversations, members, locks, notifier, restarts, boards);
        }

        @Bean
        ConversationAccounts conversationAccounts(ConversationService conversations) {
            return new ConversationAccounts(conversations);
        }

        @Bean
        ConversationService conversationService(ConversationAccess access, ConversationRepository conversations,
                                                ConversationLocks locks, ConversationMemberRepository members,
                                                MlsMessageRepository messages, UserBlockRepository blocks,
                                                IdentityKeyRepository identities, CryptoDeviceRepository devices,
                                                UserRepository users, BoardService boards,
                                                ConversationNotifier notifier, GroupRestarts restarts) {
            return new ConversationService(access, conversations, locks, members, messages, blocks, identities,
                    devices, users, boards, notifier, new ConversationMapper(), mock(AuthRateLimiter.class), restarts);
        }
    }

    @Autowired
    private BoardService boardService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository users;

    @Autowired
    private BoardRepository boards;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private User account(String name) {
        return transactions.execute(status -> {
            var user = new User(name, name + "-" + UUID.randomUUID() + "@example.test", "hashed");
            user.setEnabled(true);
            return users.save(user);
        });
    }

    private Integer boardOf(User owner) {
        return transactions.execute(status -> boardService.createBoard(owner, new CreateBoardRequest("Work")).id());
    }

    private void join(Integer boardId, User user, BoardRole role) {
        transactions.executeWithoutResult(status ->
                boardService.addAcceptedMember(boards.findWithMembersById(boardId).orElseThrow(), user, role));
    }

    private Long channelOf(Integer boardId) {
        return jdbc.queryForObject("select id from conversations where board_id = ?", Long.class, boardId);
    }

    private String roleIn(Long channel, User user) {
        List<String> roles = jdbc.queryForList(
                "select role from conversation_members where conversation_id = ? and user_id = ?", String.class, channel,
                user.getId());
        return roles.isEmpty() ? null : roles.getFirst();
    }

    private int seats(Long channel) {
        return jdbc.queryForObject("select count(*) from conversation_members where conversation_id = ?",
                Integer.class, channel);
    }

    private boolean needsCommit(Long channel) {
        return jdbc.queryForObject("select needs_commit from conversations where id = ?", Boolean.class, channel);
    }

    @Test
    @DisplayName("a new board gets a channel of its own, with its owner as admin and no group founded yet")
    void aNewBoardOpensAChannel() {
        var owner = account("owner");

        Integer board = boardOf(owner);

        Long channel = channelOf(board);
        assertThat(jdbc.queryForObject("select kind from conversations where id = ?", String.class, channel))
                .isEqualTo("BOARD");
        assertThat(jdbc.queryForObject("select epoch from conversations where id = ?", Long.class, channel)).isZero();
        assertThat(roleIn(channel, owner)).isEqualTo("ADMIN");
        assertThat(seats(channel)).isEqualTo(1);
    }

    @Test
    @DisplayName("members and viewers are seated as they join, and the channel waits for a commit that adds them")
    void joinersAreSeated() {
        var owner = account("owner");
        var editor = account("editor");
        var viewer = account("viewer");
        Integer board = boardOf(owner);
        Long channel = channelOf(board);

        join(board, editor, BoardRole.MEMBER);
        join(board, viewer, BoardRole.VIEWER);

        assertThat(roleIn(channel, editor)).isEqualTo("MEMBER");
        assertThat(roleIn(channel, viewer)).isEqualTo("VIEWER");
        assertThat(seats(channel)).isEqualTo(3);
        assertThat(needsCommit(channel)).isTrue();
    }

    @Test
    @DisplayName("a member removed from the board loses their seat, and the channel waits for a commit that removes them")
    void leaversLoseTheirSeat() {
        var owner = account("owner");
        var editor = account("editor");
        Integer board = boardOf(owner);
        join(board, editor, BoardRole.MEMBER);
        Long channel = channelOf(board);
        jdbc.update("update conversations set needs_commit = false where id = ?", channel);

        transactions.executeWithoutResult(status -> boardService.removeMember(owner, board, editor.getId()));

        assertThat(roleIn(channel, editor)).isNull();
        assertThat(seats(channel)).isEqualTo(1);
        assertThat(needsCommit(channel)).isTrue();
    }

    @Test
    @DisplayName("renaming a board keeps its channel as it was")
    void renamingChangesNothingInTheRoster() {
        var owner = account("owner");
        Integer board = boardOf(owner);
        Long channel = channelOf(board);

        transactions.executeWithoutResult(status ->
                boardService.renameBoard(owner, board, new PatchBoardRequest("Renamed")));

        assertThat(channelOf(board)).isEqualTo(channel);
        assertThat(seats(channel)).isEqualTo(1);
    }

    @Test
    @DisplayName("deleting a board takes its channel, seats and messages with it, and only its own")
    void deletingTheBoardTakesTheChannel() {
        var owner = account("owner");
        Integer doomed = boardOf(owner);
        Integer kept = boardOf(owner);
        Long doomedChannel = channelOf(doomed);
        Long keptChannel = channelOf(kept);
        jdbc.update("insert into mls_messages (conversation_id, seq, epoch, kind, body, created_at, expires_at) "
                + "values (?, 1, 0, 'APPLICATION', ?, now(), now())", doomedChannel, new byte[] {1});

        transactions.executeWithoutResult(status -> boardService.deleteBoard(owner, doomed));

        assertThat(jdbc.queryForObject("select count(*) from conversations where id = ?", Integer.class, doomedChannel))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from conversation_members where conversation_id = ?",
                Integer.class, doomedChannel)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from mls_messages where conversation_id = ?", Integer.class,
                doomedChannel)).isZero();
        assertThat(seats(keptChannel)).isEqualTo(1);
    }

    @Test
    @DisplayName("deleting the owner's account leaves the board's channel to the person who inherits the board")
    void deletingTheOwnersAccount() {
        var owner = account("owner");
        var heir = account("heir");
        Integer board = boardOf(owner);
        join(board, heir, BoardRole.MEMBER);
        Long channel = channelOf(board);

        userService.deleteUser(owner.getId());

        assertThat(roleIn(channel, owner)).isNull();
        assertThat(roleIn(channel, heir)).isEqualTo("ADMIN");
        assertThat(seats(channel)).isEqualTo(1);
        assertThat(needsCommit(channel)).isTrue();
    }

    @Test
    @DisplayName("deleting a member's account takes their seat in every board channel they were in")
    void deletingAMembersAccount() {
        var owner = account("owner");
        var editor = account("editor");
        Integer board = boardOf(owner);
        join(board, editor, BoardRole.MEMBER);
        Long channel = channelOf(board);

        userService.deleteUser(editor.getId());

        assertThat(roleIn(channel, editor)).isNull();
        assertThat(roleIn(channel, owner)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("deleting the account that owns a board nobody else is on takes the board and its channel")
    void deletingTheOnlyMembersAccount() {
        var owner = account("owner");
        Integer board = boardOf(owner);
        Long channel = channelOf(board);

        userService.deleteUser(owner.getId());

        assertThat(jdbc.queryForObject("select count(*) from conversations where id = ?", Integer.class, channel))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from boards where id = ?", Integer.class, board)).isZero();
    }

    @Test
    @DisplayName("a board has at most one channel")
    void oneChannelPerBoard() {
        var owner = account("owner");
        Integer board = boardOf(owner);

        assertThat(jdbc.queryForObject("select count(*) from conversations where board_id = ?", Integer.class, board))
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "insert into conversations (kind, board_id, group_id, epoch, next_seq, needs_commit, "
                        + "history_visibility, who_can_add, created_at, last_activity_at) "
                        + "values ('BOARD', ?, ?, 0, 1, false, 'JOINED', 'ADMINS', now(), now())", board,
                UUID.randomUUID().toString().getBytes()))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
