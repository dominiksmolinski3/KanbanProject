package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CryptoDeviceSweepTest {

    @Test
    @DisplayName("revokes devices unused for 180 days, then drops the key packages of every revoked device")
    void revokesThenDeletes() {
        Instant now = Instant.parse("2026-11-19T04:15:00Z");
        CryptoDeviceRepository devices = mock(CryptoDeviceRepository.class);
        KeyPackageRepository keyPackages = mock(KeyPackageRepository.class);
        when(devices.revokeUnusedSince(now.minus(Duration.ofDays(180)), now)).thenReturn(2);
        when(keyPackages.deleteOfRevokedDevices()).thenReturn(150);

        new CryptoDeviceSweep(devices, keyPackages, Clock.fixed(now, ZoneOffset.UTC)).sweep();

        InOrder order = inOrder(devices, keyPackages);
        order.verify(devices).revokeUnusedSince(now.minus(Duration.ofDays(180)), now);
        order.verify(keyPackages).deleteOfRevokedDevices();
    }

    @Test
    @DisplayName("a quiet night changes nothing and says nothing")
    void quietNight() {
        CryptoDeviceRepository devices = mock(CryptoDeviceRepository.class);
        KeyPackageRepository keyPackages = mock(KeyPackageRepository.class);

        new CryptoDeviceSweep(devices, keyPackages).sweep();

        InOrder order = inOrder(devices, keyPackages);
        order.verify(devices).revokeUnusedSince(any(), any());
        order.verify(keyPackages).deleteOfRevokedDevices();
    }
}
