package pl.myproject.kanbanproject2.mail;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The provider's vocabulary for what became of a message, and the half of it that means "it did
 * not arrive".
 *
 * <p>Azure reports a per-recipient {@code DeliveryStatus} on every message it accepts. Seven values
 * are documented, on exactly one page - the email-logs concept doc rather than the table-schema
 * reference, which names the column and not its values. That distinction has already cost this
 * project once: the first draft of the Log Analytics bounce alert matched
 * {@code ("Failed", "Suppressed")} and silently excluded {@code Bounced} itself, which is the word
 * the alert is named after.
 *
 * <p><b>This list exists twice and a build guard is what keeps the two copies honest.</b> The other
 * copy is the KQL in {@code terraform/modules/diagnostics/main.tf}, which is what mails an operator
 * when something bounces; this one is what the application writes on the row and what a person
 * reading the outbox sees. Edit either and everything still compiles, plans and passes - while the
 * alert and the table quietly disagree about what counts as a failure.
 * {@code BounceStatusesMatchAlertTest} reads the Terraform and fails the build when they do. Same
 * shape as {@link DeadLetterAlertTest}, one file over.
 *
 * <p>Nothing here validates an <em>incoming</em> status against this set. A provider that adds an
 * eighth value must still be recorded rather than refused: the column stores whatever arrived, and
 * this set only decides what gets counted as undelivered.
 */
public final class MailDeliveryStatuses {

    /** The one that means it arrived. */
    public static final String DELIVERED = "Delivered";

    /**
     * The statuses that mean a message this application believes it sent did not reach anybody.
     *
     * <p>Ordered as the alert's KQL orders them, so a reader comparing the two files by eye is
     * comparing two lists rather than two sets. The guard compares them as sets, because order is
     * not the thing that matters.
     */
    public static final List<String> UNDELIVERED =
            List.of("Bounced", "Failed", "Quarantined", "FilteredSpam", "Suppressed");

    private static final Set<String> UNDELIVERED_LOWERCASE = UNDELIVERED.stream()
            .map(status -> status.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private MailDeliveryStatuses() {
    }

    /**
     * Whether this status means the message did not arrive.
     *
     * <p>Case-insensitive on purpose. The status arrives as JSON over a webhook rather than from a
     * typed client, and a provider that starts sending {@code "bounced"} one day should not make
     * the count of undelivered mail quietly go to zero - which is the most dangerous shape a bug
     * in this class could take, because nothing would look wrong.
     */
    public static boolean isUndelivered(String status) {
        return status != null && UNDELIVERED_LOWERCASE.contains(status.toLowerCase(Locale.ROOT));
    }
}
