package pl.myproject.kanbanproject2.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.service.EmailMessage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a delivery report does to the row it names, and - more of these cases than the other kind -
 * what it does when it names nothing this application sent.
 */
class MailDeliveryReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-12T10:00:00Z");

    private OutboxEmailRepository outbox;
    private MailDeliveryReportService service;

    @BeforeEach
    void setUp() {
        outbox = mock(OutboxEmailRepository.class);
        service = new MailDeliveryReportService(outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OutboxEmail sentMessage(String providerMessageId) {
        OutboxEmail row = OutboxEmail.queueing(
                new EmailMessage("someone@example.test", "Subject", "<p>h</p>", "t"),
                NOW.minusSeconds(120));
        row.accepted(NOW.minusSeconds(60), providerMessageId);
        return row;
    }

    private EventGridNotification.Data report(String messageId, String status, Instant at, String detail) {
        return new EventGridNotification.Data(
                null, messageId, "someone@example.test", status,
                detail == null ? null : new EventGridNotification.StatusDetails(detail), at);
    }

    @Test
    @DisplayName("a delivery lands on the row the provider named")
    void aDeliveryIsRecorded() {
        OutboxEmail row = sentMessage("op-1");
        when(outbox.findByProviderMessageId("op-1")).thenReturn(Optional.of(row));

        assertThat(service.record(report("op-1", "Delivered", NOW, null))).isTrue();

        verify(outbox).save(row);
        assertThat(row.getDeliveryStatus()).isEqualTo("Delivered");
        assertThat(row.getDeliveryReportedAt()).isEqualTo(NOW);
        // Still SENT. The two facts are different: SENT is what this application did, Delivered is
        // what the provider says happened afterwards, and collapsing them would lose the first.
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("a bounce keeps the provider's own word and its explanation")
    void aBounceIsRecordedWithItsReason() {
        OutboxEmail row = sentMessage("op-2");
        when(outbox.findByProviderMessageId("op-2")).thenReturn(Optional.of(row));

        service.record(report("op-2", "Bounced", NOW, "550 5.1.1 recipient rejected"));

        assertThat(row.getDeliveryStatus()).isEqualTo("Bounced");
        assertThat(row.getDeliveryDetail()).isEqualTo("550 5.1.1 recipient rejected");
        assertThat(MailDeliveryStatuses.isUndelivered(row.getDeliveryStatus())).isTrue();
    }

    @Test
    @DisplayName("a report about a message this deployment never sent is ignored, not an error")
    void anUnknownMessageIsIgnored() {
        // Ordinary rather than exceptional: every row queued before V16 has no id, and a
        // subscription pointed at a second environment reports on its messages too.
        when(outbox.findByProviderMessageId("op-unknown")).thenReturn(Optional.empty());

        assertThat(service.record(report("op-unknown", "Delivered", NOW, null))).isFalse();

        verify(outbox, never()).save(any());
    }

    @Test
    @DisplayName("a report naming no message at all is ignored rather than thrown")
    void aReportWithNoMessageIdIsIgnored() {
        assertThat(service.record(report(null, "Delivered", NOW, null))).isFalse();
        assertThat(service.record(report("  ", "Delivered", NOW, null))).isFalse();
        assertThatCode(() -> service.record(null)).doesNotThrowAnyException();

        verify(outbox, never()).save(any());
    }

    @Test
    @DisplayName("an older report cannot overwrite a newer one, whichever arrives first")
    void reportsAreOrderedByTheProvidersClockRatherThanByArrival() {
        // This is the case a webhook has no defence against other than the timestamp: Azure sends
        // one report per attempt, they travel over a network, and a retry can overtake the thing
        // it is retrying. Taking the last to arrive would let OutForDelivery land on top of
        // Delivered and leave the row saying something that stopped being true.
        OutboxEmail row = sentMessage("op-3");
        when(outbox.findByProviderMessageId("op-3")).thenReturn(Optional.of(row));

        service.record(report("op-3", "Delivered", NOW, null));
        assertThat(service.record(report("op-3", "OutForDelivery", NOW.minusSeconds(30), null))).isFalse();

        assertThat(row.getDeliveryStatus()).isEqualTo("Delivered");
        assertThat(row.getDeliveryReportedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a later report does replace an earlier one")
    void aNewerReportWins() {
        OutboxEmail row = sentMessage("op-4");
        when(outbox.findByProviderMessageId("op-4")).thenReturn(Optional.of(row));

        service.record(report("op-4", "OutForDelivery", NOW.minusSeconds(30), null));
        assertThat(service.record(report("op-4", "Delivered", NOW, null))).isTrue();

        assertThat(row.getDeliveryStatus()).isEqualTo("Delivered");
    }

    @Test
    @DisplayName("a report with no timestamp is stamped on arrival rather than dropped")
    void aReportWithoutATimestampUsesTheClock() {
        OutboxEmail row = sentMessage("op-5");
        when(outbox.findByProviderMessageId("op-5")).thenReturn(Optional.of(row));

        assertThat(service.record(report("op-5", "Delivered", null, null))).isTrue();

        assertThat(row.getDeliveryReportedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("an unrecognised status is stored rather than refused")
    void anUnknownStatusIsStillRecorded() {
        // A provider that adds an eighth value must not make this endpoint start throwing reports
        // away - the column is the provider's vocabulary and the set in MailDeliveryStatuses only
        // decides what gets counted as a failure.
        OutboxEmail row = sentMessage("op-6");
        when(outbox.findByProviderMessageId("op-6")).thenReturn(Optional.of(row));

        service.record(report("op-6", "SomethingNobodyHasSeen", NOW, null));

        assertThat(row.getDeliveryStatus()).isEqualTo("SomethingNobodyHasSeen");
        assertThat(MailDeliveryStatuses.isUndelivered(row.getDeliveryStatus())).isFalse();
    }

    @Test
    @DisplayName("the undelivered set is matched without regard to case")
    void statusMatchingIsCaseInsensitive() {
        // The most dangerous shape a bug in this class could take: a provider sending "bounced"
        // one day would take the count of undelivered mail to zero and nothing would look wrong.
        assertThat(MailDeliveryStatuses.isUndelivered("bounced")).isTrue();
        assertThat(MailDeliveryStatuses.isUndelivered("FILTEREDSPAM")).isTrue();
        assertThat(MailDeliveryStatuses.isUndelivered(MailDeliveryStatuses.DELIVERED)).isFalse();
        assertThat(MailDeliveryStatuses.isUndelivered(null)).isFalse();
    }
}
