package pl.myproject.kanbanproject2.user.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wall between the row and the browser. Two things are being asserted, and the second is the
 * one that would be expensive to get wrong: that the digest never appears in what is handed out,
 * and that the two instants are not the same instant. A chain rotates on every renewal, so the
 * row's own {@code issuedAt} is the last time this browser asked for an access token - reporting it
 * as the sign-in would tell somebody every session started fifteen minutes ago.
 */
class ActiveDeviceMapperTest {

    private static final Instant SIGNED_IN = Instant.parse("2026-08-01T09:00:00Z");
    private static final Instant RENEWED = Instant.parse("2026-09-02T11:30:00Z");

    private final ActiveDeviceMapper mapper = new ActiveDeviceMapper();

    private static RefreshToken row() {
        var user = new User("someone", "someone@example.test", "hashed");
        user.setId(7);
        return new RefreshToken(
                "a".repeat(64), user, RENEWED, RENEWED.plusSeconds(2_592_000),
                SIGNED_IN.plusSeconds(7_776_000), SIGNED_IN,
                new DeviceContext("203.0.113.7", "Mozilla/5.0 (X11; Linux x86_64)"));
    }

    @Test
    @DisplayName("carries what identifies a device to its owner, and nothing that identifies the token")
    void carriesTheLabelAndNotTheCredential() {
        ActiveDeviceDto device = mapper.apply(row());

        assertThat(device.ipAddress()).isEqualTo("203.0.113.7");
        assertThat(device.userAgent()).isEqualTo("Mozilla/5.0 (X11; Linux x86_64)");
        assertThat(device.expiresAt()).isEqualTo(RENEWED.plusSeconds(2_592_000));
        assertThat(device.toString())
                .as("the digest is a credential's fingerprint and this record goes to a browser")
                .doesNotContain("aaaa");
    }

    @Test
    @DisplayName("signed-in is the chain's start; last-seen is the live row's own issue")
    void separatesTheSignInFromTheRenewal() {
        ActiveDeviceDto device = mapper.apply(row());

        assertThat(device.signedInAt()).isEqualTo(SIGNED_IN);
        assertThat(device.lastSeenAt()).isEqualTo(RENEWED);
    }

    @Test
    @DisplayName("a row from before V9 has no device details, and that is a blank cell rather than a failure")
    void toleratesARowWithNoDeviceDetails() {
        var user = new User("someone", "someone@example.test", "hashed");
        user.setId(7);
        var legacy = new RefreshToken("b".repeat(64), user, RENEWED, RENEWED.plusSeconds(60),
                RENEWED.plusSeconds(120), RENEWED, DeviceContext.unknown());

        ActiveDeviceDto device = mapper.apply(legacy);

        assertThat(device.ipAddress()).isNull();
        assertThat(device.userAgent()).isNull();
        assertThat(device.signedInAt()).isEqualTo(RENEWED);
    }
}
