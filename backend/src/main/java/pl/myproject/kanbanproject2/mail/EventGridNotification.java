package pl.myproject.kanbanproject2.mail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * One entry of an Event Grid POST, in the only two shapes this application cares about.
 *
 * <p>Event Grid posts an <em>array</em> of these, always, even for a single event and even for the
 * handshake it performs before it will send anything at all. Both shapes share one envelope and
 * differ only in {@code eventType} and in what {@code data} carries, so this is one record with a
 * {@code data} object holding the union rather than a polymorphic hierarchy - two event types do
 * not earn a type hierarchy, and the fields that do not apply are simply null.
 *
 * <p>Unknown properties are ignored deliberately, and it is not laziness. The envelope carries
 * {@code topic}, {@code metadataVersion}, {@code dataVersion} and more that nothing here reads, and
 * the provider is free to add to both the envelope and the data. A webhook that refuses a payload
 * because it grew a field is a webhook that stops recording deliveries on the day Azure ships a
 * change, silently, with the only symptom being that the outbox stops learning anything.
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

            /*
             * When the provider attempted it. The report's own clock rather than ours, which is
             * what lets two reports about one message be ordered without trusting the order they
             * arrived in.
             */
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
