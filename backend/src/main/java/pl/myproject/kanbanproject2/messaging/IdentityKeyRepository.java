package pl.myproject.kanbanproject2.messaging;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IdentityKeyRepository extends JpaRepository<IdentityKey, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT identity FROM IdentityKey identity WHERE identity.userId = :userId")
    Optional<IdentityKey> findForUpdate(@Param("userId") Integer userId);
}
