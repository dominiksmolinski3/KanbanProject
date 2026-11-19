package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class KeyDirectoryTest {

    private static final byte[] IDENTITY_KEY = filled(1);
    private static final byte[] DEVICE_KEY = filled(2);

    @Test
    @DisplayName("each key log entry hashes the one before it, to the bytes frontend/src/__tests__/crypto/keyLog.test.ts expects")
    void entryHashesMatchTheClientVector() {
        byte[] first = KeyDirectory.entryHash(null, KeyChange.IDENTITY, IDENTITY_KEY);
        byte[] second = KeyDirectory.entryHash(first, KeyChange.DEVICE_ADDED, DEVICE_KEY);

        assertThat(HexFormat.of().formatHex(first))
                .isEqualTo("380bae0315108c17b5388ab144288085cde8d5e1465e60aa45771d89795f6427");
        assertThat(HexFormat.of().formatHex(second))
                .isEqualTo("f141371413cdd43231466d1df241ea15fa62379eb223f00a04592f1f6f48f948");
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
