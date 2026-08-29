package pl.myproject.kanbanproject2.task;

import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryMapper;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link TaskService} whose only real collaborator is the repository under test.
 *
 * <p>The constructor takes eight dependencies, most of which a fetching test has no opinion about.
 * Naming them once here keeps each test to the one stub that is actually the subject.
 */
final class TaskServiceTestSupport {

    private TaskServiceTestSupport() {
    }

    static TaskService withRepository(TaskRepository taskRepository) {
        var historyRepository = mock(TaskColumnHistoryRepository.class);
        when(historyRepository.findByTaskOrderByChangedAtDesc(any())).thenReturn(List.of());

        return new TaskService(
                taskRepository,
                mock(UserRepository.class),
                new TaskMapper(),
                mock(UserService.class),
                historyRepository,
                mock(TaskColumnHistoryMapper.class),
                mock(ColumnRepository.class),
                mock(RowRepository.class));
    }
}
