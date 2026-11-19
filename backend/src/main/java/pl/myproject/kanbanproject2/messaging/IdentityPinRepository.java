package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IdentityPinRepository extends JpaRepository<IdentityPin, Integer> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE IdentityPin pin SET pin.failedAttempts = pin.failedAttempts + 1 "
            + "WHERE pin.userId = :userId AND pin.failedAttempts < :limit")
    int spendAttempt(@Param("userId") Integer userId, @Param("limit") int limit);
}
