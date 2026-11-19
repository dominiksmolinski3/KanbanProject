package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ConversationDeviceRepository extends JpaRepository<ConversationDevice, Long> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO conversation_devices (conversation_id, device_id)
            VALUES (:conversationId, :deviceId)
            ON CONFLICT (conversation_id, device_id) DO NOTHING
            """, nativeQuery = true)
    int record(@Param("conversationId") Long conversationId, @Param("deviceId") Long deviceId);

    @Query("SELECT COUNT(holder) > 0 FROM ConversationDevice holder, ConversationMember member "
            + "WHERE holder.conversation.id = :conversationId AND member.conversation.id = :conversationId "
            + "AND member.user = holder.device.user AND holder.device.revokedAt IS NULL")
    boolean hasLiveHolder(@Param("conversationId") Long conversationId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM ConversationDevice holder WHERE holder.conversation.id = :conversationId")
    int forgetAll(@Param("conversationId") Long conversationId);
}
