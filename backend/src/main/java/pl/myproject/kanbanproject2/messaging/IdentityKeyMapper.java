package pl.myproject.kanbanproject2.messaging;

import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class IdentityKeyMapper implements Function<IdentityKey, IdentityKeyDto> {

    @Override
    public IdentityKeyDto apply(IdentityKey key) {
        return new IdentityKeyDto(key.getPublicKey(), key.getCreatedAt(), key.hasRecoveryKey());
    }
}
