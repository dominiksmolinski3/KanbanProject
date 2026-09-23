package pl.myproject.kanbanproject2.user.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceContextTest {
    @Test
    @DisplayName("a user agent longer than the column is cut, not refused - a login is not the place to be strict")
    void truncatesAnOverlongUserAgent() {
        String tooLong = "M".repeat(DeviceContext.MAX_USER_AGENT_LENGTH + 50);

        var device = new DeviceContext("203.0.113.7", tooLong);

        assertThat(device.userAgent()).hasSize(DeviceContext.MAX_USER_AGENT_LENGTH);
        assertThat(tooLong).startsWith(device.userAgent());
    }

    @Test
    @DisplayName("an address longer than the column is cut too, for the same reason")
    void truncatesAnOverlongAddress() {
        var device = new DeviceContext("9".repeat(120), null);

        assertThat(device.ipAddress()).hasSize(DeviceContext.MAX_IP_ADDRESS_LENGTH);
    }

    @Test
    @DisplayName("blank is stored as nothing, so a device list shows an empty cell rather than an empty string")
    void blankBecomesNull() {
        var device = new DeviceContext("   ", "\t\n");

        assertThat(device.ipAddress()).isNull();
        assertThat(device.userAgent()).isNull();
    }

    @Test
    @DisplayName("ordinary values are kept as they are, trimmed")
    void keepsOrdinaryValues() {
        var device = new DeviceContext(" 198.51.100.4 ", " Mozilla/5.0 ");

        assertThat(device.ipAddress()).isEqualTo("198.51.100.4");
        assertThat(device.userAgent()).isEqualTo("Mozilla/5.0");
    }

    @Test
    @DisplayName("unknown is a session with nothing to say about itself, not a broken one")
    void unknownIsEmptyRatherThanAbsent() {
        assertThat(DeviceContext.unknown().ipAddress()).isNull();
        assertThat(DeviceContext.unknown().userAgent()).isNull();
    }

    @Test
    @DisplayName("only an address and a browser together identify a device")
    void identifiableNeedsBothHalves() {
        assertThat(new DeviceContext("198.51.100.4", "Mozilla/5.0").isIdentifiable()).isTrue();
        assertThat(new DeviceContext("198.51.100.4", null).isIdentifiable()).isFalse();
        assertThat(new DeviceContext(null, "Mozilla/5.0").isIdentifiable()).isFalse();
        assertThat(new DeviceContext("unknown", "Mozilla/5.0").isIdentifiable())
                .as("ClientIpResolver's placeholder is not an address")
                .isFalse();
    }
}
