package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;
import java.util.Optional;

@Repository
public interface KeyLogRepository extends JpaRepository<KeyLogEntry, Long> {

    Optional<KeyLogEntry> findFirstByUserOrderByIdDesc(User user);

    @Query("SELECT entry FROM KeyLogEntry entry WHERE entry.user.id = :userId AND entry.id > :after ORDER BY entry.id")
    List<KeyLogEntry> findAfter(@Param("userId") Integer userId, @Param("after") long after, Pageable page);
}
