package pl.myproject.kanbanproject2.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A second guard over the same two files {@link DeadLetterAlertTest} watches, on a different rule:
 * which statuses mean "did not arrive" is written down twice - {@link
 * MailDeliveryStatuses#UNDELIVERED} here, the KQL in {@code terraform/modules/diagnostics/main.tf}
 * for the alert - and nothing connects them, so they can silently drift apart. The bounce alert's
 * first draft matched {@code ("Failed", "Suppressed")} and excluded {@code Bounced} itself, caught
 * only by reading Microsoft's docs. Compared as sets, since order between the two files is not a
 * rule.
 */
class BounceStatusesMatchAlertTest {

    /** Tests run with {@code backend/} as the working directory, so the repository root is up one. */
    private static final Path DIAGNOSTICS =
            Path.of("..", "terraform", "modules", "diagnostics", "main.tf");

    /** {@code | where DeliveryStatus in ("Bounced", "Failed", ...)} - one line, quoted values. */
    private static final Pattern DELIVERY_STATUS_CLAUSE =
            Pattern.compile("DeliveryStatus\\s+in\\s*\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    @Test
    @DisplayName("the alert fires on exactly the statuses the application counts as undelivered")
    void theAlertAndTheApplicationAgreeOnWhatCountsAsAFailure() throws IOException {
        assertThat(DIAGNOSTICS)
                .as("the Terraform module holding the bounce alert has moved or gone")
                .exists();

        assertThat(statusesTheAlertMatches())
                .as("the alert mails somebody about one set of statuses and the outbox counts "
                        + "another - whichever side is narrower stops reporting, silently")
                .isEqualTo(new LinkedHashSet<>(MailDeliveryStatuses.UNDELIVERED));
    }

    @Test
    @DisplayName("delivered is not on the undelivered list, which is the one way this could be inverted")
    void deliveredIsNotAFailure() {
        assertThat(MailDeliveryStatuses.UNDELIVERED)
                .doesNotContain(MailDeliveryStatuses.DELIVERED)
                .isNotEmpty();
    }

    private static Set<String> statusesTheAlertMatches() throws IOException {
        String terraform = Files.readString(DIAGNOSTICS, StandardCharsets.UTF_8);
        Matcher clause = DELIVERY_STATUS_CLAUSE.matcher(terraform);

        assertThat(clause.find())
                .as("no `DeliveryStatus in (...)` clause in the diagnostics module - the bounce "
                        + "alert has been rewritten or removed, and this guard is now watching nothing")
                .isTrue();

        Set<String> statuses = new LinkedHashSet<>();
        Matcher value = QUOTED.matcher(clause.group(1));
        while (value.find()) {
            statuses.add(value.group(1));
        }
        return statuses;
    }
}
