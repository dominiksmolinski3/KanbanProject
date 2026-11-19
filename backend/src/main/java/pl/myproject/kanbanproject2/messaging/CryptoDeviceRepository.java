package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CryptoDeviceRepository extends JpaRepository<CryptoDevice, Long> {

    @Query("SELECT device FROM CryptoDevice device WHERE device.user = :user AND device.revokedAt IS NULL "
            + "ORDER BY device.createdAt")
    List<CryptoDevice> findLiveFor(@Param("user") User user);

    @Query("SELECT device FROM CryptoDevice device WHERE device.id = :id AND device.user = :user "
            + "AND device.revokedAt IS NULL")
    Optional<CryptoDevice> findLive(@Param("user") User user, @Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CryptoDevice device SET device.revokedAt = :when "
            + "WHERE device.user = :user AND device.revokedAt IS NULL")
    int revokeAllFor(@Param("user") User user, @Param("when") Instant when);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CryptoDevice device SET device.revokedAt = :when "
            + "WHERE device.sessionChain = :chain AND device.revokedAt IS NULL")
    int revokeOnChain(@Param("chain") UUID chain, @Param("when") Instant when);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CryptoDevice device SET device.revokedAt = :when "
            + "WHERE device.revokedAt IS NULL AND device.lastSeenAt < :cutoff")
    int revokeUnusedSince(@Param("cutoff") Instant cutoff, @Param("when") Instant when);
}
