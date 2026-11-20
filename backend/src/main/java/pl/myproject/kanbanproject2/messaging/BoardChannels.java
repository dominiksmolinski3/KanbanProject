package pl.myproject.kanbanproject2.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardDeleting;
import pl.myproject.kanbanproject2.board.BoardRole;
import pl.myproject.kanbanproject2.board.BoardRosterChanged;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.user.User;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BoardChannels {

    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final ConversationLocks locks;
    private final ConversationNotifier notifier;
    private final GroupRestarts restarts;
    private final BoardService boards;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public BoardChannels(ConversationRepository conversations, ConversationMemberRepository members,
                         ConversationLocks locks, ConversationNotifier notifier, GroupRestarts restarts,
                         BoardService boards) {
        this(conversations, members, locks, notifier, restarts, boards, Clock.systemUTC());
    }

    BoardChannels(ConversationRepository conversations, ConversationMemberRepository members,
                  ConversationLocks locks, ConversationNotifier notifier, GroupRestarts restarts,
                  BoardService boards, Clock clock) {
        this.conversations = conversations;
        this.members = members;
        this.locks = locks;
        this.notifier = notifier;
        this.restarts = restarts;
        this.boards = boards;
        this.clock = clock;
    }

    @EventListener
    public void on(BoardRosterChanged changed) {
        sync(changed.board());
    }

    @EventListener
    public void on(BoardDeleting deleting) {
        conversations.findIdByBoardId(deleting.board().getId()).ifPresent(id -> {
            List<User> before = members.usersIn(id);
            conversations.deleteWithContents(id);
            notifier.notify(before, changed(id));
            log.info("Removed the channel of deleted board {}", deleting.board().getId());
        });
    }

    void sync(Board board) {
        Map<Integer, User> people = new LinkedHashMap<>();
        board.everyone().forEach(person -> people.put(person.getId(), person));
        Map<Integer, BoardRole> boardRoles = boards.memberRoles(board);
        Map<Integer, MemberRole> wanted = new LinkedHashMap<>();
        people.keySet().forEach(userId -> wanted.put(userId, roleOf(board, userId, boardRoles)));
        Instant now = clock.instant();

        Long id = conversations.findIdByBoardId(board.getId()).orElse(null);
        if (id == null) {
            Conversation created = conversations.save(
                    Conversation.forBoard(board.getId(), groupId(), now));
            people.forEach((userId, person) -> members.save(
                    new ConversationMember(created, person, wanted.get(userId), MemberState.ACTIVE, now)));
            notifier.notify(List.copyOf(people.values()), changed(created.getId()));
            log.info("Opened the channel of board {} for {} member(s)", board.getId(), people.size());
            return;
        }

        List<User> before = members.usersIn(id);
        List<ConversationMemberRepository.RosterRow> current = members.rosterOf(id);
        Map<Integer, ConversationMemberRepository.RosterRow> present = new LinkedHashMap<>();
        current.forEach(row -> present.put(row.getUserId(), row));

        List<User> joining = people.values().stream()
                .filter(person -> !present.containsKey(person.getId()))
                .toList();
        List<ConversationMemberRepository.RosterRow> leaving = current.stream()
                .filter(row -> !people.containsKey(row.getUserId()))
                .toList();
        if (!joining.isEmpty()) {
            Conversation conversation = locks.lock(id);
            joining.forEach(person -> members.save(new ConversationMember(conversation, person,
                    wanted.get(person.getId()), MemberState.ACTIVE, now)));
            conversation.requireCommit();
            conversations.save(conversation);
        }
        leaving.forEach(row -> members.deleteMembership(row.getMemberId()));
        present.forEach((userId, row) -> {
            MemberRole role = wanted.get(userId);
            if (role != null && role != row.getRole()) {
                members.setRole(row.getMemberId(), role);
            }
        });
        if (!leaving.isEmpty()) {
            conversations.requireCommit(id);
            restarts.restartOrphans(List.of(id));
        }

        Map<Integer, User> audience = new LinkedHashMap<>();
        before.forEach(person -> audience.put(person.getId(), person));
        audience.putAll(people);
        notifier.notify(List.copyOf(audience.values()), changed(id));
    }

    private static MemberRole roleOf(Board board, Integer userId, Map<Integer, BoardRole> boardRoles) {
        if (board.getOwner() != null && board.getOwner().getId().equals(userId)) {
            return MemberRole.ADMIN;
        }
        return boardRoles.get(userId) == BoardRole.VIEWER ? MemberRole.VIEWER : MemberRole.MEMBER;
    }

    private byte[] groupId() {
        byte[] id = new byte[ConversationService.GROUP_ID_BYTES];
        random.nextBytes(id);
        return id;
    }

    private static ConversationEvent changed(Long conversationId) {
        return new ConversationEvent(ConversationEventType.MEMBERS, conversationId, null, null);
    }
}
