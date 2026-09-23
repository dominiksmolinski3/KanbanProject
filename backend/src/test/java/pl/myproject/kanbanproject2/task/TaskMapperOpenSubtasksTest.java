package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.task.subtask.SubTask;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskMapperOpenSubtasksTest {
    private final TaskMapper mapper = new TaskMapper();

    @Test
    @DisplayName("counts only the subtasks that are not done")
    void countsOpenOnly() {
        var task = new Task();
        task.setSubTasks(new ArrayList<>(List.of(subTask(false), subTask(true), subTask(false))));

        assertThat(mapper.apply(task).openSubtasks()).isEqualTo(2);
    }

    @Test
    @DisplayName("a card with every subtask done, or none at all, has nothing open")
    void nothingOpen() {
        var finished = new Task();
        finished.setSubTasks(new ArrayList<>(List.of(subTask(true))));
        var bare = new Task();
        bare.setSubTasks(null);

        assertThat(mapper.apply(finished).openSubtasks()).isZero();
        assertThat(mapper.apply(bare).openSubtasks()).isZero();
    }

    private static SubTask subTask(boolean completed) {
        var subTask = new SubTask();
        subTask.setCompleted(completed);
        return subTask;
    }
}
