package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PushEndpointRepository extends JpaRepository<PushEndpoint, Long> {

    @Query("SELECT e FROM PushEndpoint e WHERE e.device.id = :deviceId AND e.kind = :kind")
    Optional<PushEndpoint> findFor(@Param("deviceId") Long deviceId, @Param("kind") PushKind kind);

    @Query("SELECT e FROM PushEndpoint e WHERE e.user.id IN :userIds")
    List<PushEndpoint> findOfUsers(@Param("userIds") Collection<Integer> userIds);

    @Modifying
    @Query("DELETE FROM PushEndpoint e WHERE e.id = :id AND e.user.id = :userId")
    int deleteOwn(@Param("id") Long id, @Param("userId") Integer userId);
}
