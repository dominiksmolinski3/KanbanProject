package pl.myproject.kanbanproject2.mail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventGridNotification(String id, String eventType, Data data) {

    public static final String VALIDATION_EVENT = "Microsoft.EventGrid.SubscriptionValidationEvent";

    public static final String DELIVERY_REPORT_EVENT = "Microsoft.Communication.EmailDeliveryReportReceived";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(

            String validationCode,

            String messageId,

            String recipient,

            String status,

            StatusDetails deliveryStatusDetails,

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
