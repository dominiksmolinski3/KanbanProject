package pl.myproject.kanbanproject2.mail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * One entry of an Event Grid POST (always sent as an array), in the two shapes this application
 * cares about - the validation handshake and a delivery report - sharing one envelope with a
 * {@code data} union rather than a polymorphic hierarchy.
 *
 * <p>Unknown properties are ignored deliberately: Azure is free to add fields to the envelope or
 * the data, and a webhook that refuses a grown payload silently stops recording deliveries the day
 * it ships.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventGridNotification(String id, String eventType, Data data) {

    /**
     * The handshake. Event Grid will not deliver to an endpoint until the endpoint has echoed a
     * code back, which is what stops anyone pointing a subscription at a URL they do not own.
     */
    public static final String VALIDATION_EVENT = "Microsoft.EventGrid.SubscriptionValidationEvent";

    /** The one this feature exists for: what became of a message Azure accepted. */
    public static final String DELIVERY_REPORT_EVENT = "Microsoft.Communication.EmailDeliveryReportReceived";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(

            /* Validation events only: the code to echo back. */
            String validationCode,

            /* Delivery reports: the id the send operation returned, which names the outbox row. */
            String messageId,

            /* Who it was for. Recorded in the log line, never used to match - addresses repeat. */
            String recipient,

            /* Delivered, Bounced, Failed, Quarantined, FilteredSpam, Suppressed - see MailDeliveryStatuses. */
            String status,

            /* Whatever the provider has to say about a failure, when it says anything. */
            StatusDetails deliveryStatusDetails,

            /* The provider's own clock, so two reports about one message can be ordered regardless
             * of arrival order. */
            Instant deliveryAttemptTimestamp) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusDetails(String statusMessage) {
    }

    boolean isValidation() {
        return VALIDATION_EVENT.equals(eventType);
    }

    boolean isDeliveryReport() {
        return DELIVERY_REPORT_EVENT.equals(eventType);
    }
}
