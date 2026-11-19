package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class KeyPackageClaimer {

    private final KeyPackageRepository keyPackages;

    @Transactional
    public Optional<byte[]> claim(Long deviceId) {
        Optional<KeyPackage> oneTime = keyPackages.lockOneTime(deviceId);
        if (oneTime.isPresent()) {
            keyPackages.delete(oneTime.get());
            return Optional.of(oneTime.get().getData());
        }
        return keyPackages.findLastResort(deviceId).map(KeyPackage::getData);
    }
}
