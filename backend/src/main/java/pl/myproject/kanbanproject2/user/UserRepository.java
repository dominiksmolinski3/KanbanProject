package pl.myproject.kanbanproject2.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findUserByEmail(String email);

    // Every lookup goes through here so that an address typed in another case finds the same account.
    default Optional<User> findByEmail(String email) {
        return findUserByEmail(EmailAddresses.normalise(email));
    }
}
