package pl.myproject.kanbanproject2.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A guard over a string shared between {@link OutboxRelay} and
 * {@code terraform/modules/diagnostics/main.tf} that no compiler checks:
 * {@link OutboxRelay#DEAD_LETTER_MARKER} is logged on give-up and matched by a Log Analytics rule
 * that mails the alert address. Renaming, changing or rewording either side still compiles, plans
 * and passes while the alert silently stops firing.
 *
 * <p>Deliberately does not skip when the file is missing - a guard that quietly turns itself off is
 * worse than no guard.
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
