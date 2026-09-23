package pl.myproject.kanbanproject2.mail;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class MailDeliveryStatuses {
    public static final String DELIVERED = "Delivered";

    public static final List<String> UNDELIVERED =
            List.of("Bounced", "Failed", "Quarantined", "FilteredSpam", "Suppressed");

    private static final Set<String> UNDELIVERED_LOWERCASE = UNDELIVERED.stream()
            .map(status -> status.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private MailDeliveryStatuses() {
    }

    public static boolean isUndelivered(String status) {
        return status != null && UNDELIVERED_LOWERCASE.contains(status.toLowerCase(Locale.ROOT));
    }

    public static String canonical(String status) {
        return UNDELIVERED.stream().filter(known -> known.equalsIgnoreCase(status)).findFirst().orElse(status);
    }
}
