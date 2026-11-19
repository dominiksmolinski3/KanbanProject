package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;
import java.util.Set;

@Repository
public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    @Query("SELECT COUNT(block) > 0 FROM UserBlock block "
            + "WHERE (block.blocker.id = :userId AND block.blocked.id = :otherId) "
            + "OR (block.blocker.id = :otherId AND block.blocked.id = :userId)")
    boolean eitherBlocks(@Param("userId") Integer userId, @Param("otherId") Integer otherId);

    @Query("SELECT block FROM UserBlock block JOIN FETCH block.blocked WHERE block.blocker = :user "
            + "ORDER BY block.createdAt DESC")
    List<UserBlock> findMadeBy(@Param("user") User user);

    @Query("SELECT block.blocked.id FROM UserBlock block WHERE block.blocker = :user")
    Set<Integer> blockedBy(@Param("user") User user);

    @Query("SELECT block.blocker.id FROM UserBlock block WHERE block.blocked = :user")
    Set<Integer> blockersOf(@Param("user") User user);

    @Query("SELECT COUNT(block) > 0 FROM UserBlock block WHERE block.blocker = :user AND block.blocked.id = :blockedId")
    boolean blocks(@Param("user") User user, @Param("blockedId") Integer blockedId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM UserBlock block WHERE block.blocker = :user AND block.blocked.id = :blockedId")
    int unblock(@Param("user") User user, @Param("blockedId") Integer blockedId);
}
