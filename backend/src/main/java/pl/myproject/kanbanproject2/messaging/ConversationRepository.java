package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.user.User;

import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByDirectKey(String directKey);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Conversation conversation SET conversation.needsCommit = true WHERE conversation.id IN "
            + "(SELECT member.conversation.id FROM ConversationMember member WHERE member.user = :user)")
    int requireCommitFor(@Param("user") User user);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Conversation conversation SET conversation.needsCommit = true WHERE conversation.id = :id")
    int requireCommit(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Conversation conversation SET conversation.createdBy = NULL WHERE conversation.createdBy = :user")
    int forgetCreator(@Param("user") User user);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM Conversation conversation WHERE conversation.id = :id")
    int deleteWithContents(@Param("id") Long id);
}
