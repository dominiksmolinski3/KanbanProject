package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
public class CryptoDeviceSweep {

    static final Duration UNUSED = Duration.ofDays(180);

    private final CryptoDeviceRepository devices;
    private final KeyPackageRepository keyPackages;
    private final KeyDirectory keyDirectory;
    private final Clock clock;

    @Autowired
    public CryptoDeviceSweep(CryptoDeviceRepository devices, KeyPackageRepository keyPackages,
                             KeyDirectory keyDirectory) {
        this(devices, keyPackages, keyDirectory, Clock.systemUTC());
    }

    CryptoDeviceSweep(CryptoDeviceRepository devices, KeyPackageRepository keyPackages, KeyDirectory keyDirectory,
                      Clock clock) {
        this.devices = devices;
        this.keyPackages = keyPackages;
        this.keyDirectory = keyDirectory;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 4 * * *")
    @Transactional
    public void sweep() {
        Instant now = clock.instant();
        List<CryptoDevice> unused = devices.findUnusedSince(now.minus(UNUSED));
        int revoked = devices.revokeUnusedSince(now.minus(UNUSED), now);
        keyDirectory.devicesRevoked(unused, now);
        int deleted = keyPackages.deleteOfRevokedDevices();
        if (revoked > 0 || deleted > 0) {
            log.info("Revoked {} unused messaging device(s) and deleted {} key package(s)", revoked, deleted);
        }
    }
}
