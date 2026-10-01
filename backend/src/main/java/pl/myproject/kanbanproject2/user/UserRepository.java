package pl.myproject.kanbanproject2.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findUserByEmail(String email);

    @Query("SELECT u.avatarBlobName FROM User u WHERE u.avatarBlobName IN :names")
    List<String> findAvatarBlobNamesIn(@Param("names") Collection<String> names);

    // Every lookup goes through here so that an address typed in another case finds the same account.
    default Optional<User> findByEmail(String email) {
        return findUserByEmail(EmailAddresses.normalise(email));
    }
}
