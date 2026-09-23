package pl.myproject.kanbanproject2.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.SupportedLocales;
import pl.myproject.kanbanproject2.user.User;

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

    private static String boardOf(Task task) {
        return task.getBoard() == null ? null : task.getBoard().getName();
    }
}
