package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitScriptTest {
    private static final Path SCRIPT =
            Path.of("src", "main", "resources", "redis", "auth-rate-limit.lua");

    @Test
    @DisplayName("the read, the decision and the write happen in one script, not a round trip apart")
    void readsDecidesAndWritesInOneScript() {
        String script = read();

        assertThat(countOccurrences(script, "HMGET")).isEqualTo(1);
        assertThat(countOccurrences(script, "HSET")).isEqualTo(2);
        assertThat(countOccurrences(script, "PEXPIRE")).isEqualTo(2);
    }

    @Test
    @DisplayName("the clock comes from the caller, not from Redis")
    void theClockIsSuppliedNotRead() {
        String script = read();

        assertThat(script).doesNotContain("redis.call('TIME')").doesNotContain("redis.call(\"TIME\")");
        assertThat(script).contains("ARGV[1]");
    }

    @Test
    @DisplayName("the doubling loop is bounded, so an attacker who never goes quiet cannot make it spin")
    void theDoublingLoopIsBounded() {
        String script = read();

        assertThat(script).contains("doublings > 32");
    }

    private static long countOccurrences(String haystack, String needle) {
        return haystack.lines()
                .flatMap(line -> java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(needle))
                        .matcher(line).results())
                .count();
    }

    private static String read() {
        try {
            return Files.readString(SCRIPT);
        } catch (IOException e) {
            throw new UncheckedIOException("redis/auth-rate-limit.lua has moved or gone", e);
        }
    }
}
