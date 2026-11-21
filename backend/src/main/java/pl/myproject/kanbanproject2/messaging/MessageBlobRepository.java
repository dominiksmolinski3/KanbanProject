package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageBlobRepository extends JpaRepository<MessageBlob, Long> {

    @Query("SELECT b FROM MessageBlob b WHERE b.id = :id AND b.conversation.id = :conversationId")
    Optional<MessageBlob> findInConversation(@Param("id") Long id, @Param("conversationId") Long conversationId);

    @Query("SELECT COALESCE(SUM(b.sizeBytes), 0) FROM MessageBlob b WHERE b.conversation.id = :conversationId")
    long totalSize(@Param("conversationId") Long conversationId);

    @Query("SELECT b.blobName FROM MessageBlob b WHERE b.blobName IN :names")
    List<String> findBlobNamesIn(@Param("names") Collection<String> names);

    @Query("SELECT b FROM MessageBlob b WHERE b.expiresAt <= :now ORDER BY b.expiresAt")
    List<MessageBlob> findExpired(@Param("now") Instant now, Pageable page);
}
