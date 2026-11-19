package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Slf4j
@Component
public class CryptoDeviceSweep {

    private final CryptoDeviceRepository devices;
    private final KeyPackageRepository keyPackages;
    private final Clock clock;

    @Autowired
    public CryptoDeviceSweep(CryptoDeviceRepository devices, KeyPackageRepository keyPackages) {
        this(devices, keyPackages, Clock.systemUTC());
    }

    CryptoDeviceSweep(CryptoDeviceRepository devices, KeyPackageRepository keyPackages, Clock clock) {
        this.devices = devices;
        this.keyPackages = keyPackages;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 4 * * *")
    @Transactional
    public void sweep() {
        int revoked = devices.revokeWithoutLiveSession(clock.instant());
        int deleted = keyPackages.deleteOfRevokedDevices();
        if (revoked > 0 || deleted > 0) {
            log.info("Revoked {} messaging device(s) whose session ended and deleted {} key package(s)",
                    revoked, deleted);
        }
    }
}
