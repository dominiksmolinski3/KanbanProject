package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;
import java.util.List;

@Repository
public interface MlsMessageRepository extends JpaRepository<MlsMessage, Long> {

    @Query("SELECT message FROM MlsMessage message WHERE message.conversation = :conversation "
            + "AND message.seq > :after ORDER BY message.seq")
    List<MlsMessage> findAfter(@Param("conversation") Conversation conversation, @Param("after") long after,
                               Pageable page);

    @Query("SELECT message.conversation.id AS conversationId, COUNT(message) AS unread "
            + "FROM MlsMessage message, ConversationMember member "
            + "WHERE member.user = :user AND member.conversation = message.conversation "
            + "AND message.kind = :kind AND message.seq > member.readSeq "
            + "AND (message.sender IS NULL OR message.sender <> :user) "
            + "GROUP BY message.conversation.id")
    List<UnreadCount> countUnread(@Param("user") User user, @Param("kind") MlsMessageKind kind);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM MlsMessage message WHERE message.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    interface UnreadCount {
        Long getConversationId();

        long getUnread();
    }
}
