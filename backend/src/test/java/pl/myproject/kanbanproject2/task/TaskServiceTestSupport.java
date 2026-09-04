package pl.myproject.kanbanproject2.task;

import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.attachment.TaskAttachmentService;
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
 * <p>The constructor takes nine dependencies, most of which a fetching test has no opinion about.
 * Naming them once here keeps each test to the one stub that is actually the subject.
 */
final class TaskServiceTestSupport {

    static final TenancyFixtures.Tenant TENANT = TenancyFixtures.tenant();

    private TaskServiceTestSupport() {
    }

    static Board board() {
        return TENANT.board();
    }

    static pl.myproject.kanbanproject2.user.User caller() {
        return TENANT.caller();
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
                mock(RowRepository.class),
                TenancyFixtures.boardServiceReturning(TENANT.board()),
                mock(DeadlineNotifier.class),
                mock(TaskAttachmentService.class));
    }
}
