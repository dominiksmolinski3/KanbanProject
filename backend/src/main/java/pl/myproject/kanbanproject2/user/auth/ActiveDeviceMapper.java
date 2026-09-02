package pl.myproject.kanbanproject2.user.auth;

import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Turns a live {@link RefreshToken} row into the session a person can look at.
 *
 * <p>Same shape as every other mapper here — a {@code @Component} implementing {@code Function},
 * called as {@code mapper::apply} — and the same job: keep the entity, and the digest it carries,
 * on the service side of the wall.
 */
@Component
public class ActiveDeviceMapper implements Function<RefreshToken, ActiveDeviceDto> {

    @Override
    public ActiveDeviceDto apply(RefreshToken token) {
        return new ActiveDeviceDto(
                token.getId(),
                token.getIpAddress(),
                token.getUserAgent(),
                token.getChainStartedAt(),
                // The live row's own issue instant: the last time this chain was renewed.
                token.getIssuedAt(),
                token.getExpiresAt());
    }
}
