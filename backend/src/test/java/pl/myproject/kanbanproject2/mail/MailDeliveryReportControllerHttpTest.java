package pl.myproject.kanbanproject2.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.config.AcsMailProperties;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The only unauthenticated write in the application, driven over real HTTP with the payloads Azure
 * actually posts.
 *
 * <p>Every other route here is reached by this project's own browser code holding a token; this one
 * is reached by Event Grid, which has no account and never will. So the assertions that matter are
 * not about a service being called - they are about what an unauthenticated caller can do with it:
 * nothing without the key, nothing at all when no key is configured, and a handshake answered in
 * the body rather than in a status code, because a handshake answered with an empty 200 creates a
 * subscription that exists and silently never delivers.
 */
class MailDeliveryReportControllerHttpTest {

    private static final String KEY = "a-shared-webhook-key";

    private static final String DELIVERY_REPORT = """
            [{
              "id": "d1",
              "topic": "/subscriptions/x/resourceGroups/y/providers/Microsoft.Communication/CommunicationServices/z",
              "subject": "sender/DoNotReply@example.azurecomm.net/message/op-1",
              "eventType": "Microsoft.Communication.EmailDeliveryReportReceived",
              "dataVersion": "1.0",
              "metadataVersion": "1",
              "eventTime": "2026-09-12T10:00:02.0000000Z",
              "data": {
                "sender": "DoNotReply@example.azurecomm.net",
                "recipient": "someone@example.test",
                "messageId": "op-1",
                "status": "Delivered",
                "deliveryStatusDetails": { "statusMessage": "DestinationMailboxFull" },
                "deliveryAttemptTimestamp": "2026-09-12T10:00:01.0000000Z"
              }
            }]""";

    private static final String VALIDATION = """
            [{
              "id": "v1",
              "eventType": "Microsoft.EventGrid.SubscriptionValidationEvent",
              "data": {
                "validationCode": "512d38b6-c7b8-40c8-89fe-f46f9e9622b6",
                "validationUrl": "https://rp-eastus.eventgrid.azure.net/..."
              }
            }]""";

    private MailDeliveryReportService reports;

    @BeforeEach
    void setUp() {
        reports = mock(MailDeliveryReportService.class);
    }

    private MockMvc mvcWithKey(String configuredKey) {
        AcsMailProperties properties =
                new AcsMailProperties("endpoint=x;accesskey=y", "DoNotReply@x", Duration.ofSeconds(10), 1, configuredKey);
        return MockMvcBuilders.standaloneSetup(new MailDeliveryReportController(reports, properties))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a report with the right key reaches the service")
    void aReportWithTheKeyIsRecorded() throws Exception {
        mvcWithKey(KEY).perform(post("/mail/delivery-reports").param("key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(DELIVERY_REPORT))
                .andExpect(status().isOk());

        verify(reports).record(any());
    }

    @Test
    @DisplayName("the payload is parsed as Azure sends it, unknown envelope fields and all")
    void thePayloadAzureSendsIsUnderstood() throws Exception {
        // topic, subject, dataVersion, metadataVersion and eventTime are all in the fixture above
        // and none of them is on the record. A webhook that refused a payload for growing a field
        // would stop recording deliveries on the day Azure ships a change, with no symptom but an
        // outbox that quietly learns nothing.
        var captor = org.mockito.ArgumentCaptor.forClass(EventGridNotification.Data.class);

        mvcWithKey(KEY).perform(post("/mail/delivery-reports").param("key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(DELIVERY_REPORT))
                .andExpect(status().isOk());

        verify(reports).record(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().messageId()).isEqualTo("op-1");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().status()).isEqualTo("Delivered");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().deliveryAttemptTimestamp())
                .isEqualTo(java.time.Instant.parse("2026-09-12T10:00:01Z"));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().deliveryStatusDetails().statusMessage())
                .isEqualTo("DestinationMailboxFull");
    }

    @Test
    @DisplayName("the handshake is answered in the body, which is what Azure reads")
    void theValidationHandshakeIsEchoed() throws Exception {
        // Answering 200 with nothing here creates a subscription that exists in the portal and
        // never delivers anything, which is indistinguishable from a feature nobody is using.
        mvcWithKey(KEY).perform(post("/mail/delivery-reports").param("key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(VALIDATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validationResponse").value("512d38b6-c7b8-40c8-89fe-f46f9e9622b6"));

        verify(reports, never()).record(any());
    }

    @Test
    @DisplayName("no key presented is a 404, and nothing is recorded")
    void withoutAKeyThereIsNothingHere() throws Exception {
        mvcWithKey(KEY).perform(post("/mail/delivery-reports")
                        .contentType(MediaType.APPLICATION_JSON).content(DELIVERY_REPORT))
                .andExpect(status().isNotFound());

        verify(reports, never()).record(any());
    }

    @Test
    @DisplayName("the wrong key is a 404 too - the same answer as a wrong URL")
    void theWrongKeyIsTheSameAnswerAsAWrongUrl() throws Exception {
        mvcWithKey(KEY).perform(post("/mail/delivery-reports").param("key", "not-the-key")
                        .contentType(MediaType.APPLICATION_JSON).content(DELIVERY_REPORT))
                .andExpect(status().isNotFound());

        verify(reports, never()).record(any());
    }

    @Test
    @DisplayName("with no key configured the endpoint does not exist, whatever is presented")
    void unconfiguredMeansClosed() throws Exception {
        // The state of every fresh clone and every CI run. An empty presented key must not match
        // empty configuration, which is why blankness is checked before the comparison.
        MockMvc unconfigured = mvcWithKey("");

        unconfigured.perform(post("/mail/delivery-reports").param("key", "")
                        .contentType(MediaType.APPLICATION_JSON).content(DELIVERY_REPORT))
                .andExpect(status().isNotFound());
        unconfigured.perform(post("/mail/delivery-reports")
                        .contentType(MediaType.APPLICATION_JSON).content(VALIDATION))
                .andExpect(status().isNotFound());

        verify(reports, never()).record(any());
    }

    @Test
    @DisplayName("an event type this endpoint was not subscribed for is ignored rather than refused")
    void anUnrelatedEventTypeIsIgnored() throws Exception {
        // A subscription filter is something somebody edits in Azure. An application that answered
        // non-2xx to the result would turn that edit into a retry storm of its own.
        String unrelated = """
                [{"id":"u1","eventType":"Microsoft.Communication.SMSDeliveryReportReceived","data":{}}]""";

        mvcWithKey(KEY).perform(post("/mail/delivery-reports").param("key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(unrelated))
                .andExpect(status().isOk());

        verify(reports, never()).record(any());
    }

    @Test
    @DisplayName("an empty batch is a 200 - Event Grid reads anything else as try again")
    void anEmptyBatchIsAccepted() throws Exception {
        mvcWithKey(KEY).perform(post("/mail/delivery-reports").param("key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk());
    }
}
