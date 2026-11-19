package pl.myproject.kanbanproject2.messaging;

import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class CryptoDeviceMapper implements Function<LiveCryptoDevice, CryptoDeviceDto> {

    @Override
    public CryptoDeviceDto apply(LiveCryptoDevice live) {
        CryptoDevice device = live.device();
        return new CryptoDeviceDto(device.getId(), live.sessionId(), device.getSignatureKey(),
                device.getCreatedAt(), live.keyPackages());
    }
}
