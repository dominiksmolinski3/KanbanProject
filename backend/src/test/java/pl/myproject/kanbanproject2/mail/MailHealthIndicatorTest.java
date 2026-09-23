package pl.myproject.kanbanproject2.mail;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import pl.myproject.kanbanproject2.service.EmailSender;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailHealthIndicatorTest {
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    private final OutboxEmailRepository outbox = mock(OutboxEmailRepository.class);
    private final EmailSender transport = mock(EmailSender.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MailHealthIndicator indicator =
            new MailHealthIndicator(outbox, transport, Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);

    @Test
    @DisplayName("mail that is not configured is out of service, and says how much it has thrown away")
    void unconfiguredMailIsOutOfService() {
        when(transport.deliversMessages()).thenReturn(false);
        when(outbox.countByStatus(OutboxStatus.DROPPED)).thenReturn(12L);
        when(outbox.countByStatus(OutboxStatus.PENDING)).thenReturn(1L);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsEntry("dropped", 12L).containsEntry("pending", 1L);
    }

    @Test
    @DisplayName("a message the relay gave up on today is down")
    void aRecentDeadLetterIsDown() {
        when(transport.deliversMessages()).thenReturn(true);
        when(outbox.countByStatus(OutboxStatus.FAILED)).thenReturn(3L);
        when(outbox.countByStatusAndCreatedAtGreaterThanEqual(eq(OutboxStatus.FAILED), eq(NOW.minus(MailHealthIndicator.RECENT))))
                .thenReturn(2L);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("failedRecently", 2L).containsEntry("failed", 3L);
    }

    @Test
    @DisplayName("an old failure stays in the total and stops holding the status red")
    void anOldDeadLetterNoLongerFails() {
        when(transport.deliversMessages()).thenReturn(true);
        when(outbox.countByStatus(OutboxStatus.FAILED)).thenReturn(7L);
        when(outbox.countByStatusAndCreatedAtGreaterThanEqual(eq(OutboxStatus.FAILED), eq(NOW.minus(MailHealthIndicator.RECENT))))
                .thenReturn(0L);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("failed", 7L);
    }

    @Test
    @DisplayName("a working relay is up, with the queue depth as a detail")
    void aWorkingRelayIsUp() {
        when(transport.deliversMessages()).thenReturn(true);
        when(outbox.countByStatus(OutboxStatus.PENDING)).thenReturn(4L);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("pending", 4L).containsEntry("failed", 0L);
    }

    @Test
    @DisplayName("a join that is working reads as reported rising with sent")
    void reportsThatLandAreVisible() {
        Instant since = NOW.minus(MailHealthIndicator.RECENT);
        when(transport.deliversMessages()).thenReturn(true);
        when(outbox.countByStatusAndCreatedAtGreaterThanEqual(eq(OutboxStatus.SENT), eq(since)))
                .thenReturn(9L);
        when(outbox.countByStatusAndDeliveryStatusIsNotNullAndCreatedAtGreaterThanEqual(
                eq(OutboxStatus.SENT), eq(since)))
                .thenReturn(8L);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("sent", 9L)
                .containsEntry("reported", 8L)
                .containsEntry("sentAwaitingReport", 1L);
    }

    @Test
    @DisplayName("a join that is broken is the case undelivered cannot show: sent climbs, reported does not")
    void aBrokenJoinIsDistinguishableFromQuietSuccess() {
        Instant since = NOW.minus(MailHealthIndicator.RECENT);
        when(transport.deliversMessages()).thenReturn(true);
        when(outbox.countByStatusAndCreatedAtGreaterThanEqual(eq(OutboxStatus.SENT), eq(since)))
                .thenReturn(40L);
        when(outbox.countByStatusAndDeliveryStatusIsNotNullAndCreatedAtGreaterThanEqual(
                eq(OutboxStatus.SENT), eq(since)))
                .thenReturn(0L);
        when(outbox.countByStatusAndCreatedAtGreaterThanEqualAndProviderMessageIdIsNull(
                eq(OutboxStatus.SENT), eq(since)))
                .thenReturn(40L);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("undelivered", 0L)
                .containsEntry("sent", 40L)
                .containsEntry("reported", 0L)
                .containsEntry("sentWithoutProviderId", 40L);
    }

    @Test
    @DisplayName("the new counts are details rather than a status, like undelivered")
    void theNewCountsNeverChangeTheStatus() {
        Instant since = NOW.minus(MailHealthIndicator.RECENT);
        when(transport.deliversMessages()).thenReturn(true);
        when(outbox.countByStatusAndCreatedAtGreaterThanEqual(eq(OutboxStatus.SENT), eq(since)))
                .thenReturn(100L);
        when(outbox.countByStatusAndDeliveryStatusIsNotNullAndCreatedAtGreaterThanEqual(
                eq(OutboxStatus.SENT), eq(since)))
                .thenReturn(0L);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("nothing loads a row, because a pending row's body is a live verification code")
    void nothingReadsTheBodies() {
        when(transport.deliversMessages()).thenReturn(true);

        indicator.health();

        verify(outbox, never()).findAll();
        verify(outbox, never()).claimBatch(any(), any(), anyInt());
    }

    @Test
    @DisplayName("the pending gauge reads through the same query the health detail uses")
    void thePendingGaugeTracksTheQueueDepth() {
        when(outbox.countByStatus(OutboxStatus.PENDING)).thenReturn(3L);

        assertThat(meterRegistry.get(MailHealthIndicator.PENDING_GAUGE).gauge().value()).isEqualTo(3.0);

        when(outbox.countByStatus(OutboxStatus.PENDING)).thenReturn(9L);

        assertThat(meterRegistry.get(MailHealthIndicator.PENDING_GAUGE).gauge().value()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("a row a relay is posting right now is counted, so a worked queue is not an empty one")
    void inFlightRowsAreVisible() {
        when(transport.deliversMessages()).thenReturn(true);
        when(outbox.countByStatus(OutboxStatus.PENDING)).thenReturn(0L);
        when(outbox.countByStatus(OutboxStatus.SENDING)).thenReturn(6L);

        Health health = indicator.health();

        assertThat(health.getDetails()).containsEntry("pending", 0L).containsEntry("sending", 6L);
    }
}
