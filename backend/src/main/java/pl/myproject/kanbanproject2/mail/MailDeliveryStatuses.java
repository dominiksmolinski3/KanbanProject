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
 * <p><b>This list exists once.</b> It used to exist twice - the bounce alert's KQL repeated it, with
 * a build guard to keep the copies agreeing. The alert now counts
 * {@code kanban.mail.delivery.undelivered}, which {@code MailDeliveryReportService} increments for
 * these statuses and no others, so what counts as a failure is decided here alone.
 *
 * <p>Nothing here validates an <em>incoming</em> status: the column stores whatever arrived, and
 * this set only decides what counts as undelivered.
 */
public final class MailDeliveryStatuses {

    /** The one that means it arrived. */
    public static final String DELIVERED = "Delivered";

    /**
     * The statuses that mean a message this application believes it sent did not reach anybody.
     * Only this list decides it: the bounce alert counts {@code kanban.mail.delivery.undelivered},
     * which {@code MailDeliveryReportService} increments for exactly these.
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

    /** The spelling in {@link #UNDELIVERED} for an undelivered status, whatever case it arrived in. */
    public static String canonical(String status) {
        return UNDELIVERED.stream().filter(known -> known.equalsIgnoreCase(status)).findFirst().orElse(status);
    }
}
