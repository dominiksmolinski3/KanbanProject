package pl.myproject.kanbanproject2.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.service.EmailDeliveryException;
import pl.myproject.kanbanproject2.service.EmailMessage;
import pl.myproject.kanbanproject2.service.EmailSender;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relay is where every failure that used to be a caller's problem now lives, so its failure
 * behaviour is most of what is worth testing.
 *
 * <p>Four claims: a message the provider takes is marked sent and never posted twice; a refusal
 * waits and comes back rather than being lost or retried immediately; enough refusals stop; and one
 * bad row does not take the rest of the batch with it - which is the property the old synchronous
 * path could not have, because there was no batch.
 *
 * <p>What this cannot show, and no unit test can: that the relay's schedule fires, and that two
 * replicas would post everything twice. The first is Spring's; the second is the reason the
 * deployment pins replicas to 1 and is written down rather than asserted.
 */
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

    private final OutboxEmailRepository outbox = mock(OutboxEmailRepository.class);
    private final EmailSender transport = mock(EmailSender.class);
    private final OutboxRelay relay = new OutboxRelay(outbox, transport, Clock.fixed(NOW, ZoneOffset.UTC));

    private OutboxEmail row(String to) {
        return OutboxEmail.queueing(
                new EmailMessage(to, "Account Verification", "<p>123456</p>", "123456"), NOW);
    }

    private void due(OutboxEmail... rows) {
        when(transport.deliversMessages()).thenReturn(true);
        when(outbox.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(OutboxStatus.PENDING, NOW))
                .thenReturn(new ArrayList<>(List.of(rows)));
    }

    @Test
    @DisplayName("a message the provider takes is posted once and marked sent")
    void anAcceptedMessageIsMarkedSent() {
        OutboxEmail queued = row("someone@example.test");
        due(queued);

        relay.deliverPending();

        verify(transport).send(queued.asMessage());
        assertThat(queued.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(queued.getSentAt()).isEqualTo(NOW);
        assertThat(queued.getAttempts()).isEqualTo(1);
        verify(outbox).save(queued);
    }

    @Test
    @DisplayName("an empty queue does not reach the transport at all")
    void nothingDueIsNothingDone() {
        when(outbox.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(any(), any()))
                .thenReturn(List.of());

        relay.deliverPending();

        verify(transport, never()).send(any());
        verify(outbox, never()).save(any(OutboxEmail.class));
    }

    @Nested
    @DisplayName("when the provider refuses")
    class Refusals {

        private final EmailDeliveryException refusal =
                new EmailDeliveryException("wrapper", new RuntimeException("unknown sender address"));

        @Test
        @DisplayName("the row stays pending, waits, and records what the provider said")
        void aRefusalIsRetriedLater() {
            OutboxEmail queued = row("someone@example.test");
            due(queued);
            doThrow(refusal).when(transport).send(any());

            relay.deliverPending();

            assertThat(queued.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(queued.getAttempts()).isEqualTo(1);
            assertThat(queued.getNextAttemptAt()).isEqualTo(NOW.plus(OutboxRelay.FIRST_BACKOFF));
            // The provider's complaint, not this application's wrapper around it.
            assertThat(queued.getLastError()).isEqualTo("unknown sender address");
            verify(outbox).save(queued);
        }

        @Test
        @DisplayName("the wait doubles, so a provider having a bad afternoon is not hammered through it")
        void theBackoffDoubles() {
            OutboxEmail queued = row("someone@example.test");

            queued.refused("nope", NOW, Duration.ofMinutes(1));
            assertThat(queued.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
            queued.refused("nope", NOW, Duration.ofMinutes(1));
            assertThat(queued.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
            queued.refused("nope", NOW, Duration.ofMinutes(1));
            assertThat(queued.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(4)));
        }

        @Test
        @DisplayName("enough refusals stop, and the row says why rather than being retried forever")
        void enoughRefusalsGiveUp() {
            OutboxEmail queued = row("someone@example.test");
            for (int attempt = 1; attempt < OutboxEmail.MAX_ATTEMPTS; attempt++) {
                queued.refused("nope", NOW, Duration.ofMinutes(1));
                assertThat(queued.getStatus()).isEqualTo(OutboxStatus.PENDING);
            }

            queued.refused("unknown sender address", NOW, Duration.ofMinutes(1));

            assertThat(queued.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(queued.getLastError()).isEqualTo("unknown sender address");
        }

        @Test
        @DisplayName("one bad row does not stop the rest of the batch")
        void oneRefusalDoesNotStopTheBatch() {
            OutboxEmail broken = row("broken@example.test");
            OutboxEmail fine = row("fine@example.test");
            due(broken, fine);
            doThrow(refusal).when(transport).send(eq(broken.asMessage()));

            relay.deliverPending();

            assertThat(broken.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(fine.getStatus()).isEqualTo(OutboxStatus.SENT);
            verify(outbox).save(broken);
            verify(outbox).save(fine);
        }

        @Test
        @DisplayName("a refusal with nothing behind it still records something readable")
        void aRefusalWithNoCauseStillSaysSomething() {
            OutboxEmail queued = row("someone@example.test");
            due(queued);
            doThrow(new EmailDeliveryException("the provider refused it", null)).when(transport).send(any());

            relay.deliverPending();

            assertThat(queued.getLastError()).isEqualTo("the provider refused it");
        }
    }

    @Test
    @DisplayName("with no mail account configured the rows are dropped rather than recorded as sent")
    void unconfiguredMailDropsRatherThanLies() {
        OutboxEmail queued = row("someone@example.test");
        when(transport.deliversMessages()).thenReturn(false);
        when(outbox.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(OutboxStatus.PENDING, NOW))
                .thenReturn(List.of(queued));

        relay.deliverPending();

        verify(transport, never()).send(any());
        assertThat(queued.getStatus()).isEqualTo(OutboxStatus.DROPPED);
        assertThat(queued.getLastError()).contains("no mail account");
        verify(outbox).saveAll(List.of(queued));
    }
}
