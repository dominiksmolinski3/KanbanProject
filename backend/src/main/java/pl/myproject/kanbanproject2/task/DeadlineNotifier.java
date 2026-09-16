package pl.myproject.kanbanproject2.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.SupportedLocales;
import pl.myproject.kanbanproject2.user.User;

/**
 * Mails a task's assignees when it passes its deadline, called once from
 * {@link TaskService#checkAllTasksDeadlines()} on the transition into {@code expired}. A failed
 * send is logged and swallowed rather than propagated, so one unreachable mailbox can't stop the
 * batch or roll back the flag already written. Only assigned users are told — mailing every board
 * member on every sweep would be noise, not a notification.
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
     * Only the board's own name, or null when it has none. The English stand-ins this used to
     * assemble here belonged to whichever language the message is written in, so they moved into
     * the mail bundles and this passes the facts instead.
     */
    private static String boardOf(Task task) {
        return task.getBoard() == null ? null : task.getBoard().getName();
    }
}
