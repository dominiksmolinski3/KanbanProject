package pl.myproject.kanbanproject2.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findUserByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT user FROM User user WHERE user.email = :email")
    Optional<User> findLockedUserByEmail(@Param("email") String email);

    @Query("SELECT user.sessionsRevokedAt FROM User user WHERE user.id = :id")
    Optional<Instant> findSessionsRevokedAt(@Param("id") Integer id);

    @Query("SELECT u.avatarBlobName FROM User u WHERE u.avatarBlobName IN :names")
    List<String> findAvatarBlobNamesIn(@Param("names") Collection<String> names);

    // Every lookup goes through here so that an address typed in another case finds the same account.
    default Optional<User> findByEmail(String email) {
        return findUserByEmail(EmailAddresses.normalise(email));
    }

    default Optional<User> findByEmailForUpdate(String email) {
        return findLockedUserByEmail(EmailAddresses.normalise(email));
    }
}
