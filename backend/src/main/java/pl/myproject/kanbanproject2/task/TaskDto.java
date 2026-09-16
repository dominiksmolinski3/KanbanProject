package pl.myproject.kanbanproject2.task;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * {@code version} is the task's {@code @Version}, carried out so the client can send it back on a
 * later PATCH — for the slower race the row lock can't see, where a form saved after someone
 * else's change has already committed would otherwise overwrite it silently.
 */
public record TaskDto(Integer id, Integer version, String title, Integer position, Integer columnId, Integer rowId,
                      Set<Integer> userIds, Set<String> labels, boolean completed, String description,
                      Integer parentTaskId, Set<Integer> childTaskIds, LocalDateTime deadline, boolean expired,
                      boolean dailyFocus) {
}