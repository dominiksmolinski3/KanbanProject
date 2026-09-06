package pl.myproject.kanbanproject2.task.activity;

import org.springframework.stereotype.Component;

import java.util.function.Function;

/** The usual shape: a {@code @Component} implementing {@code Function}, called as a method ref. */
@Component
public class TaskActivityMapper implements Function<TaskActivity, TaskActivityDto> {

    @Override
    public TaskActivityDto apply(TaskActivity activity) {
        if (activity == null) {
            return null;
        }
        var task = activity.getTask();
        var actor = activity.getActor();
        return new TaskActivityDto(
                activity.getId(),
                task == null ? null : task.getId(),
                activity.getTaskTitle(),
                actor == null ? null : actor.getId(),
                activity.getActorName(),
                activity.getType(),
                activity.getDetail(),
                activity.getOccurredAt());
    }
}
