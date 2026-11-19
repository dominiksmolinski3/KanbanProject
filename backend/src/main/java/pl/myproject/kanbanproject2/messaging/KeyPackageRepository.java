package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.user.User;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KeyPackageRepository extends JpaRepository<KeyPackage, Long> {

    long countByDeviceAndLastResortFalse(CryptoDevice device);

    @Query("SELECT keyPackage.device.id AS deviceId, COUNT(keyPackage) AS available FROM KeyPackage keyPackage "
            + "WHERE keyPackage.device IN :devices AND keyPackage.lastResort = false GROUP BY keyPackage.device.id")
    List<DeviceKeyPackageCount> countOneTimeByDevice(@Param("devices") Collection<CryptoDevice> devices);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM KeyPackage keyPackage WHERE keyPackage.device = :device AND keyPackage.lastResort = true")
    int deleteLastResortOf(@Param("device") CryptoDevice device);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM KeyPackage keyPackage WHERE keyPackage.device.id IN "
            + "(SELECT device.id FROM CryptoDevice device WHERE device.user = :user)")
    int deleteAllOf(@Param("user") User user);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM KeyPackage keyPackage WHERE keyPackage.device = :device")
    int deleteAllOfDevice(@Param("device") CryptoDevice device);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM KeyPackage keyPackage WHERE keyPackage.device.id IN "
            + "(SELECT device.id FROM CryptoDevice device WHERE device.sessionChain = :chain)")
    int deleteAllOnChain(@Param("chain") UUID chain);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM KeyPackage keyPackage WHERE keyPackage.device.id IN "
            + "(SELECT device.id FROM CryptoDevice device WHERE device.revokedAt IS NOT NULL)")
    int deleteOfRevokedDevices();

    @Query(value = """
            SELECT * FROM key_packages
            WHERE device_id = :deviceId AND last_resort = false
            ORDER BY id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<KeyPackage> lockOneTime(@Param("deviceId") Long deviceId);

    @Query("SELECT keyPackage FROM KeyPackage keyPackage WHERE keyPackage.device.id = :deviceId "
            + "AND keyPackage.lastResort = true")
    Optional<KeyPackage> findLastResort(@Param("deviceId") Long deviceId);

    interface DeviceKeyPackageCount {
        Long getDeviceId();

        long getAvailable();
    }
}
