package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceLinkRepository extends JpaRepository<DeviceLink, UUID> {

    @Query("SELECT l FROM DeviceLink l WHERE l.id = :id AND l.user.id = :userId AND l.expiresAt > :now")
    Optional<DeviceLink> findLive(@Param("id") UUID id, @Param("userId") Integer userId, @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM DeviceLink l WHERE l.user.id = :userId")
    int deleteAllOf(@Param("userId") Integer userId);

    @Modifying
    @Query("DELETE FROM DeviceLink l WHERE l.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
