package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.service.EmailDeliveryException;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.User;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DeadlineNotifierTest {

    private final EmailService emailService = mock(EmailService.class);
    private final DeadlineNotifier notifier = new DeadlineNotifier(emailService);

    private static Task overdueTask(User... assignees) {
        var board = new Board();
        board.setName("Delivery");
        var task = new Task();
        task.setId(7);
        task.setTitle("Ship the release");
        task.setBoard(board);
        task.setDeadline(LocalDateTime.of(2026, 8, 1, 17, 0));
        task.setUsers(new HashSet<>(Set.of(assignees)));
        return task;
    }

    private static User user(String email) {
        var user = new User();
        user.setEmail(email);
        return user;
    }

    @Test
    @DisplayName("every assignee with an address is mailed once, with the title in the subject")
    void mailsEachAssignee() {
        notifier.notifyExpired(overdueTask(user("a@example.com")));

        verify(emailService).sendEmail(eq("a@example.com"), contains("Ship the release"), contains("Delivery"));
    }

    @Test
    @DisplayName("an assignee with no address is skipped rather than sent a blank message")
    void skipsAddresslessAssignees() {
        notifier.notifyExpired(overdueTask(user(null), user("  "), user("real@example.com")));

        verify(emailService).sendEmail(eq("real@example.com"), any(), any());
        verify(emailService, never()).sendEmail(eq(null), any(), any());
    }

    @Test
    @DisplayName("a failed send is swallowed so the rest of the batch still goes out")
    void oneFailedSendDoesNotStopTheOthers() {
        doThrow(new EmailDeliveryException("the provider refused it", new RuntimeException("400")))
                .when(emailService).sendEmail(eq("broken@example.com"), any(), any());

        assertThatCode(() -> notifier.notifyExpired(
                overdueTask(user("broken@example.com"), user("ok@example.com"))))
                .doesNotThrowAnyException();

        verify(emailService).sendEmail(eq("ok@example.com"), any(), any());
    }

    @Test
    @DisplayName("a task with no assignees mails nobody")
    void noAssigneesNoMail() {
        var task = new Task();
        task.setUsers(null);

        notifier.notifyExpired(task);

        verifyNoInteractions(emailService);
    }
}
