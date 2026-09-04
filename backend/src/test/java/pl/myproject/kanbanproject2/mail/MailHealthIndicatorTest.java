package pl.myproject.kanbanproject2.mail;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The indicator exists to make two deliberately quiet failures loud again, so what is worth
 * asserting is which status each one produces and that neither of them is the status a probe would
 * act on.
 *
 * <p>What this cannot show, and is stated on the class instead: that the container's probes address
 * the readiness and liveness groups rather than the root endpoint, so a mail account nobody
 * configured cannot restart the application. That is a fact about the Dockerfile and the Terraform
 * probe paths, not about this class.
 */
class MailHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    private final OutboxEmailRepository outbox = mock(OutboxEmailRepository.class);
    private final EmailSender transport = mock(EmailSender.class);
    private final MailHealthIndicator indicator =
            new MailHealthIndicator(outbox, transport, Clock.fixed(NOW, ZoneOffset.UTC));

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
    @DisplayName("nothing loads a row, because a pending row's body is a live verification code")
    void nothingReadsTheBodies() {
        when(transport.deliversMessages()).thenReturn(true);

        indicator.health();

        verify(outbox, never()).findAll();
        verify(outbox, never())
                .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(any(), any());
    }
}
