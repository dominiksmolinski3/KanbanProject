package pl.myproject.kanbanproject2.user.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * The lookup every rotation makes. The digest is unique, so this is one index hit and the row
     * it finds is the only place the token's state is written down.
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Withdraws every live token an account holds, in one statement.
     *
     * <p>A bulk update rather than a load-and-save loop because the callers are the two moments
     * where correctness beats convenience: a password change and a chain-reuse detection. Both want
     * every session gone before the request returns, and neither knows or cares how many there are.
     * The trade is that the persistence context does not see it — {@code clearAutomatically} keeps
     * a caller from reading a stale copy back in the same transaction.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshToken token SET token.revokedAt = :when "
            + "WHERE token.user = :user AND token.revokedAt IS NULL")
    int revokeAllForUser(@Param("user") User user, @Param("when") Instant when);

    /**
     * Drops rows nobody can present any more.
     *
     * <p>A revoked row still earns its keep for as long as the token it stands for could be
     * replayed; past its own expiry it proves nothing a check on {@code expiresAt} would not
     * already reject, and this table only ever grows.
     */
    @Modifying
    @Query("DELETE FROM RefreshToken token WHERE token.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
