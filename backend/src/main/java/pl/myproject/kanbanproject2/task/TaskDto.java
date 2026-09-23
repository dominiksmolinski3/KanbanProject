package pl.myproject.kanbanproject2.task;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * {@code version} is the task's {@code @Version}, carried out so the client can send it back on a
 * later PATCH — for the slower race the row lock can't see, where a form saved after someone
 * else's change has already committed would otherwise overwrite it silently.
 *
 * <p>{@code openSubtasks} is how many of the card's subtasks are not done, which is all the board
 * needs to warn before a card is completed with work left on it. It rides on the listing rather than
 * a subtask fetch per card, so a board of forty cards is one request instead of forty-one, and a
 * subtask ticked on somebody else's screen reaches this one through the ordinary
 * {@code refreshTasks()} that a {@code SUBTASKS} board frame asks for.
 */
public record TaskDto(Integer id, Integer version, String title, Integer position, Integer columnId, Integer rowId,
                      Set<Integer> userIds, Set<String> labels, boolean completed, String description,
                      Integer parentTaskId, Set<Integer> childTaskIds, LocalDateTime deadline, boolean expired,
                      boolean dailyFocus, int openSubtasks) {
}