package pl.myproject.kanbanproject2.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.user.auth.SessionEnded;

import java.time.Clock;

@Component
@RequiredArgsConstructor
public class CryptoDeviceSessions {

    private final CryptoDeviceRepository devices;
    private final KeyPackageRepository keyPackages;

    // Synchronous on purpose: ending a session from Signed-in devices revokes its key in the same transaction.
    @EventListener
    public void on(SessionEnded ended) {
        keyPackages.deleteAllOnChain(ended.chain());
        devices.revokeOnChain(ended.chain(), Clock.systemUTC().instant());
    }
}
