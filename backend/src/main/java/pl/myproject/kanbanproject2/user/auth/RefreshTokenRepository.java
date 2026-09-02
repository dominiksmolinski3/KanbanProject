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

    /**
     * The lookup every rotation makes. The digest is unique, so this is one index hit and the row
     * it finds is the only place the token's state is written down.
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Every session an account can still use, newest renewal first.
     *
     * <p>Live means both halves: not withdrawn, and not past its expiry. A revoked row is kept so
     * that replaying the token it stands for is detectable, and an expired one is kept until the
     * daily sweep drops it - neither is a session anybody is holding, and showing either in a
     * device list would offer a "sign out" button for something already signed out.
     *
     * <p>There is exactly one live row per chain, because rotation withdraws the row it replaces.
     * So this is a list of sessions even though it is a query over tokens.
     */
    List<RefreshToken> findByUserAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(
            User user, Instant now);

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
