package pl.myproject.kanbanproject2.messaging;

import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class IdentityBackupMapper implements Function<IdentityKey, IdentityBackupDto> {

    @Override
    public IdentityBackupDto apply(IdentityKey key) {
        return new IdentityBackupDto(key.getPublicKey(), key.getWrappedPrivate());
    }
}
