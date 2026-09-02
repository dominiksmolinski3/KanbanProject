package pl.myproject.kanbanproject2.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.SupportedLocales;
import pl.myproject.kanbanproject2.user.User;

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
                emailService.sendTaskOverdue(address, task.getTitle(), boardOf(task),
                        task.getDeadline(), SupportedLocales.toLocale(user.getLocale()));
            } catch (Exception e) {
                log.error("Failed to send the deadline notification for task {} to {}",
                        task.getId(), address, e);
            }
        }
    }

    /**
     * Only the board's own name, and null when it has none.
     *
     * <p>The stand-ins this used to hold - "your board", "Untitled task", and a {@code d MMM yyyy}
     * rendering of the deadline - were English sentences assembled here and handed to the template
     * as finished text. They belong to whichever language the message is written in, so they moved
     * into the bundles and this passes the facts.
     */
    private static String boardOf(Task task) {
        return task.getBoard() == null ? null : task.getBoard().getName();
    }
}
