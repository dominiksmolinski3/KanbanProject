package pl.myproject.kanbanproject2.mail;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The provider's vocabulary for what became of a message, and the half that means "it did not
 * arrive". The first draft of the Log Analytics bounce alert matched
 * {@code ("Failed", "Suppressed")} and silently excluded {@code Bounced} itself - the word the alert
 * is named after.
 *
 * <p><b>This list exists twice, and a build guard keeps the copies honest.</b> The other copy is the
 * KQL in {@code terraform/modules/diagnostics/main.tf}; edit either alone and everything still
 * compiles and passes while the alert and the table quietly disagree about what counts as a
 * failure. {@code BounceStatusesMatchAlertTest} reads the Terraform and fails the build when they
 * do - same shape as {@link DeadLetterAlertTest}.
 *
 * <p>Nothing here validates an <em>incoming</em> status: the column stores whatever arrived, and
 * this set only decides what counts as undelivered.
 */
public final class MailDeliveryStatuses {

    /** The one that means it arrived. */
    public static final String DELIVERED = "Delivered";

    /**
     * The statuses that mean a message this application believes it sent did not reach anybody.
     * Ordered as the alert's KQL orders them, so a reader comparing the two files by eye compares
     * two lists; the guard itself compares them as sets.
     */
    public static final List<String> UNDELIVERED =
            List.of("Bounced", "Failed", "Quarantined", "FilteredSpam", "Suppressed");

    private static final Set<String> UNDELIVERED_LOWERCASE = UNDELIVERED.stream()
            .map(status -> status.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private MailDeliveryStatuses() {
    }

    /**
     * Whether this status means the message did not arrive. Case-insensitive on purpose: the status
     * arrives as JSON over a webhook, and a provider sending {@code "bounced"} one day should not
     * silently make the undelivered count go to zero.
     */
    public static boolean isUndelivered(String status) {
        return status != null && UNDELIVERED_LOWERCASE.contains(status.toLowerCase(Locale.ROOT));
    }
}
