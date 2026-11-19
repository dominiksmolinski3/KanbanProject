package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface MlsWelcomeRepository extends JpaRepository<MlsWelcome, Long> {

    @Query("SELECT welcome FROM MlsWelcome welcome JOIN FETCH welcome.conversation "
            + "WHERE welcome.device = :device ORDER BY welcome.id")
    List<MlsWelcome> findFor(@Param("device") CryptoDevice device);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM MlsWelcome welcome WHERE welcome.id = :id AND welcome.device = :device")
    int deleteForDevice(@Param("id") Long id, @Param("device") CryptoDevice device);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM MlsWelcome welcome WHERE welcome.device IN :devices")
    int deleteAllFor(@Param("devices") Collection<CryptoDevice> devices);
}
