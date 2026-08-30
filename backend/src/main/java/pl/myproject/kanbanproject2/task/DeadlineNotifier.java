package pl.myproject.kanbanproject2.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.User;

import java.time.format.DateTimeFormatter;

/**
 * Mails a task's assignees when it passes its deadline.
 *
 * <p>Called from the deadline sweep in {@link TaskService#checkAllTasksDeadlines()}, once, on the
 * transition into {@code expired}. The sweep has already persisted the flag by the time this runs,
 * so a send that fails is logged and swallowed rather than propagated: one unreachable mailbox must
 * not stop the rest of the batch or roll back the flag that was just written.
 *
 * <p>Only assigned users are told. An unassigned overdue task has nobody it is overdue <em>for</em>,
 * and mailing every board member on every sweep would be noise, not a notification.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeadlineNotifier {

    private static final DateTimeFormatter DEADLINE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    private final EmailService emailService;

    public void notifyExpired(Task task) {
        if (task.getUsers() == null) {
            return;
        }
        for (User user : task.getUsers()) {
            String address = user.getEmail();
            if (address == null || address.isBlank()) {
                continue;
            }
            try {
                emailService.sendEmail(address, subjectFor(task), bodyFor(task));
            } catch (Exception e) {
                log.error("Failed to send the deadline notification for task {} to {}",
                        task.getId(), address, e);
            }
        }
    }

    private static String subjectFor(Task task) {
        return "Task overdue: " + safeTitle(task);
    }

    private static String bodyFor(Task task) {
        String deadline = task.getDeadline() == null ? "its deadline"
                : "its deadline of " + task.getDeadline().format(DEADLINE_FORMAT);
        String board = task.getBoard() == null || task.getBoard().getName() == null
                ? "your board" : task.getBoard().getName();
        return "<html><body style=\"font-family: Arial, sans-serif;\">"
                + "<div style=\"background-color: #f5f5f5; padding: 20px;\">"
                + "<h2 style=\"color: #333;\">A task has passed its deadline</h2>"
                + "<p style=\"font-size: 16px;\"><strong>" + escape(safeTitle(task)) + "</strong> on "
                + escape(board) + " has passed " + escape(deadline) + ".</p>"
                + "<p style=\"font-size: 14px; color: #666;\">Open the board to reschedule it or move it on.</p>"
                + "</div></body></html>";
    }

    private static String safeTitle(Task task) {
        return task.getTitle() == null || task.getTitle().isBlank() ? "Untitled task" : task.getTitle();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
