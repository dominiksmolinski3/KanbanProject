package pl.myproject.kanbanproject2.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A guard over a string that two files share and no compiler reads.
 *
 * <p>{@link OutboxRelay} logs {@link OutboxRelay#DEAD_LETTER_MARKER} on the line where it gives up
 * on a message, and a Log Analytics rule in {@code terraform/modules/diagnostics/main.tf} matches
 * console log lines containing that token and mails whoever the alert address names. Nothing about
 * that coupling is visible to javac, to Terraform, or to either test suite: rename the constant,
 * change its value, or reword the KQL, and everything still compiles, plans and passes. What
 * happens instead is that the alert stops firing - and it stops firing silently, in exactly the
 * way the outbox made the underlying failure silent in the first place.
 *
 * <p>So this reads the other file. It is the same shape as {@code SessionRoutesTest} asserting that
 * two routes stay public and rate-limited, and of {@code PublicBundlePathsTest} pinning the static
 * patterns: a rule that lives in two places, checked in one.
 *
 * <p>It deliberately does not skip when the file is missing. A guard that quietly turns itself off
 * when it cannot find what it is guarding is worse than no guard, because the build stays green
 * either way and only one of those two states is honest.
 */
class DeadLetterAlertTest {

    /**
     * Tests run with {@code backend/} as the working directory, so the repository root is one level
     * up. If that ever stops being true this fails loudly, which is the intended behaviour.
     */
    private static final Path DIAGNOSTICS =
            Path.of("..", "terraform", "modules", "diagnostics", "main.tf");

    @Test
    @DisplayName("the alert query matches the token the relay actually logs")
    void theAlertMatchesTheMarker() throws IOException {
        assertThat(DIAGNOSTICS)
                .as("the Terraform module holding the dead-letter alert has moved or gone")
                .exists();

        String terraform = Files.readString(DIAGNOSTICS, StandardCharsets.UTF_8);

        assertThat(terraform)
                .as("nothing outside this process would notice a dead letter any more")
                .contains(OutboxRelay.DEAD_LETTER_MARKER);
    }

    @Test
    @DisplayName("the marker is a token rather than a phrase, so rewording the sentence cannot break it")
    void theMarkerIsNotProse() {
        assertThat(OutboxRelay.DEAD_LETTER_MARKER)
                .as("a marker with spaces in it is a sentence, and sentences get edited")
                .doesNotContain(" ")
                .hasSizeGreaterThan(6);
    }
}
