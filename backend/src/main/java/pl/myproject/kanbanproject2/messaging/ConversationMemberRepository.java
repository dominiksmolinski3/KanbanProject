package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationMemberRepository extends JpaRepository<ConversationMember, Long> {

    @Query("SELECT member FROM ConversationMember member JOIN FETCH member.conversation "
            + "WHERE member.conversation.id = :conversationId AND member.user.id = :userId")
    Optional<ConversationMember> findMembership(@Param("conversationId") Long conversationId,
                                                @Param("userId") Integer userId);

    @Query("SELECT member FROM ConversationMember member JOIN FETCH member.conversation WHERE member.user = :user")
    List<ConversationMember> findAllOf(@Param("user") User user);

    @Query("SELECT member FROM ConversationMember member JOIN FETCH member.user "
            + "WHERE member.conversation IN :conversations ORDER BY member.joinedAt, member.id")
    List<ConversationMember> findAllIn(@Param("conversations") Collection<Conversation> conversations);

    @Query("SELECT DISTINCT other.user FROM ConversationMember other WHERE other.conversation.id IN "
            + "(SELECT mine.conversation.id FROM ConversationMember mine WHERE mine.user = :user)")
    List<User> findCompanionsOf(@Param("user") User user);

    @Query("SELECT COUNT(mine) > 0 FROM ConversationMember mine, ConversationMember theirs "
            + "WHERE mine.conversation = theirs.conversation AND mine.user.id = :userId AND theirs.user.id = :otherId")
    boolean shareAConversation(@Param("userId") Integer userId, @Param("otherId") Integer otherId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM ConversationMember member WHERE member.id = :id")
    int deleteMembership(@Param("id") Long id);

    @Query("SELECT member.id AS memberId, member.conversation.id AS conversationId, "
            + "member.conversation.kind AS kind, member.role AS role, member.state AS state, member.joinedAt AS joinedAt "
            + "FROM ConversationMember member WHERE member.user = :user")
    List<MembershipRow> membershipsOf(@Param("user") User user);

    @Query("SELECT member.id AS memberId, member.conversation.id AS conversationId, "
            + "member.conversation.kind AS kind, member.role AS role, member.state AS state, member.joinedAt AS joinedAt "
            + "FROM ConversationMember member WHERE member.conversation.id = :conversationId")
    List<MembershipRow> membershipsIn(@Param("conversationId") Long conversationId);

    @Query("SELECT member.user FROM ConversationMember member WHERE member.conversation.id = :conversationId")
    List<User> usersIn(@Param("conversationId") Long conversationId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE ConversationMember member SET member.role = :role WHERE member.id = :id")
    int setRole(@Param("id") Long id, @Param("role") MemberRole role);

    interface MembershipRow {
        Long getMemberId();

        Long getConversationId();

        ConversationKind getKind();

        MemberRole getRole();

        MemberState getState();

        Instant getJoinedAt();
    }
}
