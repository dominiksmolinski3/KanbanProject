package pl.myproject.kanbanproject2.user.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(
            User user, Instant now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshToken token SET token.revokedAt = :when "
            + "WHERE token.user = :user AND token.revokedAt IS NULL")
    int revokeAllForUser(@Param("user") User user, @Param("when") Instant when);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE RefreshToken token SET token.revokedAt = :when "
            + "WHERE token.user = :user AND token.revokedAt IS NULL "
            + "AND token.ipAddress = :ipAddress AND token.userAgent = :userAgent")
    int revokeLiveForDevice(@Param("user") User user, @Param("ipAddress") String ipAddress,
                            @Param("userAgent") String userAgent, @Param("when") Instant when);

    @Modifying
    @Query("DELETE FROM RefreshToken token WHERE token.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
