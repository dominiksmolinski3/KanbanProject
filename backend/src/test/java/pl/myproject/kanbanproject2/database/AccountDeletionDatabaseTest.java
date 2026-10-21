package pl.myproject.kanbanproject2.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardMapper;
import pl.myproject.kanbanproject2.board.BoardRepository;
import pl.myproject.kanbanproject2.board.BoardRole;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.CreateBoardRequest;
import pl.myproject.kanbanproject2.board.invitation.BoardInvitation;
import pl.myproject.kanbanproject2.board.invitation.BoardInvitationRepository;
import pl.myproject.kanbanproject2.chat.Chat;
import pl.myproject.kanbanproject2.chat.ChatRepository;
import pl.myproject.kanbanproject2.chat.MessageType;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserMapper;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringJUnitConfig(DatabaseTestConfiguration.class)
@Import({BoardService.class, BoardMapper.class, UserService.class, UserMapper.class})
class AccountDeletionDatabaseTest {

    @Autowired
    private UserService userService;

    @Autowired
    private BoardService boardService;

    @Autowired
    private UserRepository users;

    @Autowired
    private BoardRepository boards;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private BoardInvitationRepository invitations;

    @Autowired
    private ChatRepository chats;

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

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    @Test
    @DisplayName("an account that owns boards, shares them, invited people and holds tasks can be deleted")
    void anOwnerCanCloseTheirAccount() {
        var owner = account("owner");
        var viewer = account("viewer");
        var member = account("member");

        Integer solo = transactions.execute(status -> boardService.currentBoard(owner).id());
        Integer shared = transactions.execute(status -> {
            var board = boards.findWithMembersById(boardService.createBoard(owner, new CreateBoardRequest("Shared")).id())
                    .orElseThrow();
            boardService.addAcceptedMember(board, viewer, BoardRole.VIEWER);
            boardService.addAcceptedMember(board, member, BoardRole.MEMBER);
            var task = new Task();
            task.setTitle("shared work");
            task.setBoard(board);
            task.getUsers().add(users.findById(owner.getId()).orElseThrow());
            task.getUsers().add(users.findById(member.getId()).orElseThrow());
            tasks.save(task);
            invitations.save(new BoardInvitation(board, "pending-" + UUID.randomUUID() + "@example.test",
                    users.findById(owner.getId()).orElseThrow()));
            return board.getId();
        });

        assertThatCode(() -> userService.deleteUser(owner.getId()))
                .as("a foreign key without ON DELETE refused the delete, which answered 409 for every account")
                .doesNotThrowAnyException();

        assertThat(count("select count(*) from users where id = ?", owner.getId())).isZero();
        assertThat(count("select count(*) from boards where id = ?", solo)).as("the board only they used").isZero();
        Board kept = boards.findWithMembersById(shared).orElseThrow();
        assertThat(kept.getOwner().getId()).as("a member who can write takes over, ahead of the viewer")
                .isEqualTo(member.getId());
        assertThat(count("select count(*) from board_invitations where board_id = ? and invited_by_id is null", shared))
                .isEqualTo(1);
        assertThat(count("select count(*) from user_task where user_id = ?", owner.getId())).isZero();
    }

    @Test
    @DisplayName("closing an account deletes its direct messages, so a later owner of the address cannot read them")
    void closingAnAccountDeletesItsDirectMessages() {
        var alice = account("alice");
        var bob = account("bob");
        var carol = account("carol");
        transactions.executeWithoutResult(status -> {
            chats.save(direct(alice.getEmail(), bob.getEmail()));
            chats.save(direct(bob.getEmail(), alice.getEmail().toUpperCase()));
            chats.save(direct(bob.getEmail(), carol.getEmail()));
        });

        userService.deleteUser(alice.getId());

        assertThat(count("select count(*) from chat_messages where lower(sender) = ? or lower(recipient_id) = ?",
                alice.getEmail(), alice.getEmail()))
                .as("a new account under the same address would inherit the thread")
                .isZero();
        assertThat(count("select count(*) from chat_messages where sender = ? and recipient_id = ?",
                bob.getEmail(), carol.getEmail()))
                .as("other people's conversations stay")
                .isEqualTo(1);
    }

    private static Chat direct(String sender, String recipient) {
        return Chat.builder()
                .type(MessageType.PRIVATE)
                .content("hello")
                .sender(sender)
                .recipientId(recipient)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
