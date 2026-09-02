package pl.myproject.kanbanproject2.task;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * {@code version} is the task's {@code @Version}, carried out so the client can send it back on a
 * later PATCH. The row-level lock already stops two overlapping transactions from clobbering each
 * other; this is for the slower race the lock cannot see - a task read into a form, changed by
 * someone else, and saved from that form minutes later, by which point the stale request's own
 * transaction reads the already-current version and the overwrite is silent.
 */
public record TaskDto(Integer id, Integer version, String title, Integer position, Integer columnId, Integer rowId,
                      Set<Integer> userIds, Set<String> labels, boolean completed, String description,
                      Integer parentTaskId, Set<Integer> childTaskIds, LocalDateTime deadline, boolean expired,
                      boolean dailyFocus) {
}