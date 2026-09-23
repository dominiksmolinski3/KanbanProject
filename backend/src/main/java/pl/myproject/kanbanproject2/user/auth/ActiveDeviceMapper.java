package pl.myproject.kanbanproject2.user.auth;

import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class ActiveDeviceMapper implements Function<RefreshToken, ActiveDeviceDto> {

    @Override
    public ActiveDeviceDto apply(RefreshToken token) {
        return new ActiveDeviceDto(
                token.getId(),
                token.getIpAddress(),
                token.getUserAgent(),
                token.getChainStartedAt(),
                token.getIssuedAt(),
                token.getExpiresAt(),
                false);
    }
}
