package pl.myproject.kanbanproject2.mail;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {
    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

    private final OutboxEmailRepository outbox = mock(OutboxEmailRepository.class);
    private final OutboxClaimer claimer = mock(OutboxClaimer.class);
    private final EmailSender transport = mock(EmailSender.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OutboxRelay relay =
            new OutboxRelay(outbox, claimer, transport, Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);

    @BeforeEach
    void nothingIsLapsedUnlessATestSaysSo() {
        when(claimer.reclaimLapsed(any(), anyInt())).thenReturn(List.of());
        when(claimer.claimDue(any(), any(), anyInt())).thenReturn(List.of());
    }

    private OutboxEmail row(String to) {
        return OutboxEmail.queueing(
                new EmailMessage(to, "Account Verification", "<p>123456</p>", "123456"), NOW);
    }

    private void due(OutboxEmail... rows) {
        when(transport.deliversMessages()).thenReturn(true);
        when(claimer.claimDue(NOW, OutboxRelay.CLAIM_LEASE, OutboxRelay.BATCH_SIZE))
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
        relay.deliverPending();

        verify(transport, never()).send(any());
        verify(outbox, never()).save(any(OutboxEmail.class));
    }

    @Nested
    @DisplayName("claiming")
    class Claiming {

        @Test
        @DisplayName("the batch is claimed rather than read, so a second relay cannot take it too")
        void theBatchIsClaimed() {
            due(row("someone@example.test"));

            relay.deliverPending();

            verify(claimer).claimDue(NOW, OutboxRelay.CLAIM_LEASE, OutboxRelay.BATCH_SIZE);
        }

        @Test
        @DisplayName("lapsed claims are put back before this pass takes anything")
        void lapsedClaimsComeBackFirst() {
            OutboxEmail abandoned = row("someone@example.test");
            abandoned.claimed(NOW, OutboxRelay.CLAIM_LEASE);
            abandoned.abandoned(NOW);
            when(claimer.reclaimLapsed(NOW, OutboxRelay.BATCH_SIZE)).thenReturn(List.of(abandoned));

            relay.deliverPending();

            assertThat(abandoned.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(abandoned.getNextAttemptAt()).isEqualTo(NOW);
            verify(transport, never()).send(any());

            InOrder order = inOrder(claimer);
            order.verify(claimer).reclaimLapsed(NOW, OutboxRelay.BATCH_SIZE);
            order.verify(claimer).claimDue(any(), any(), anyInt());
        }

        @Test
        @DisplayName("a claim charges an attempt, so a row that keeps killing the relay stops")
        void anAbandonedRowRunsOutOfAttempts() {
            OutboxEmail poison = row("someone@example.test");
            for (int attempt = 1; attempt < OutboxEmail.MAX_ATTEMPTS; attempt++) {
                poison.claimed(NOW, OutboxRelay.CLAIM_LEASE);
                poison.abandoned(NOW);
                assertThat(poison.getStatus()).isEqualTo(OutboxStatus.PENDING);
            }

            poison.claimed(NOW, OutboxRelay.CLAIM_LEASE);
            poison.abandoned(NOW);

            assertThat(poison.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(poison.getLastError()).contains("claim lapsed");
        }

        @Test
        @DisplayName("giving up on an abandoned row counts a dead letter, once")
        void anAbandonedGiveUpCountsADeadLetter() {
            OutboxEmail poison = row("someone@example.test");
            for (int attempt = 0; attempt < OutboxEmail.MAX_ATTEMPTS; attempt++) {
                poison.claimed(NOW, OutboxRelay.CLAIM_LEASE);
                poison.abandoned(NOW);
            }
            assertThat(poison.getStatus()).isEqualTo(OutboxStatus.FAILED);
            when(claimer.reclaimLapsed(NOW, OutboxRelay.BATCH_SIZE)).thenReturn(List.of(poison));

            relay.deliverPending();

            assertThat(meterRegistry.counter(OutboxRelay.DEAD_LETTER_COUNTER).count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("the lease is written where the next relay looks for it")
        void theClaimLeaseIsTheNextAttemptTime() {
            OutboxEmail claimed = row("someone@example.test");

            claimed.claimed(NOW, OutboxRelay.CLAIM_LEASE);

            assertThat(claimed.getStatus()).isEqualTo(OutboxStatus.SENDING);
            assertThat(claimed.getNextAttemptAt()).isEqualTo(NOW.plus(OutboxRelay.CLAIM_LEASE));
        }
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
        @DisplayName("giving up on a refused row counts a dead letter, once, alongside the marker line")
        void aRefusedGiveUpCountsADeadLetter() {
            OutboxEmail queued = row("someone@example.test");
            due(queued);
            doThrow(refusal).when(transport).send(any());

            for (int attempt = 0; attempt < OutboxEmail.MAX_ATTEMPTS; attempt++) {
                relay.deliverPending();
            }

            assertThat(queued.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(meterRegistry.counter(OutboxRelay.DEAD_LETTER_COUNTER).count()).isEqualTo(1.0);
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
        when(claimer.claimDue(NOW, OutboxRelay.CLAIM_LEASE, OutboxRelay.BATCH_SIZE))
                .thenReturn(List.of(queued));

        relay.deliverPending();

        verify(transport, never()).send(any());
        assertThat(queued.getStatus()).isEqualTo(OutboxStatus.DROPPED);
        assertThat(queued.getLastError()).contains("no mail account");
        verify(outbox).saveAll(List.of(queued));
    }
}
