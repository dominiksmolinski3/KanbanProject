package pl.myproject.kanbanproject2.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-user WIP limit is the only one the server actually enforces — column and row limits are
 * advisory — so what this route reports is what a client can say about it.
 */
class UserServiceWipStatusTest {

    private static final Integer USER_ID = 7;

    private UserRepository userRepository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userService = new UserService(userRepository, new UserMapper(), mock(TaskRepository.class));
    }

    @Test
    @DisplayName("reports the limit and the count, not just whether there is room")
    void describesTheWholePicture() {
        givenUser(4, 3);

        var status = userService.getWipStatus(USER_ID);

        assertThat(status.userId()).isEqualTo(USER_ID);
        assertThat(status.wipLimit()).isEqualTo(4);
        assertThat(status.assignedCount()).isEqualTo(3);
        assertThat(status.withinLimit()).isTrue();
    }

    @Test
    @DisplayName("a user at their limit is not within it - the next assignment is the one refused")
    void atTheLimitIsNotWithinIt() {
        givenUser(3, 3);

        var status = userService.getWipStatus(USER_ID);

        assertThat(status.assignedCount()).isEqualTo(3);
        assertThat(status.withinLimit()).isFalse();
    }

    @Test
    @DisplayName("a user over their limit stays over it")
    void overTheLimit() {
        givenUser(2, 5);

        assertThat(userService.getWipStatus(USER_ID).withinLimit()).isFalse();
    }

    @Test
    @DisplayName("no limit means no ceiling, and the count is still reported")
    void nullLimitMeansUnlimited() {
        givenUser(null, 12);

        var status = userService.getWipStatus(USER_ID);

        assertThat(status.wipLimit()).isNull();
        assertThat(status.assignedCount()).isEqualTo(12);
        assertThat(status.withinLimit()).isTrue();
    }

    @Test
    @DisplayName("checkWipStatus answers exactly what the DTO says, so assignment cannot drift from the report")
    void booleanShortcutAgreesWithTheDto() {
        givenUser(3, 3);
        assertThat(userService.checkWipStatus(USER_ID)).isFalse();

        givenUser(3, 1);
        assertThat(userService.checkWipStatus(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("an unknown id is a 404, not a false")
    void unknownUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getWipStatus(USER_ID))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.USER_NOT_FOUND);
    }

    private void givenUser(Integer wipLimit, int assignedTasks) {
        var user = new User();
        user.setId(USER_ID);
        user.setEmail("user@example.com");
        user.setName("Test User");
        user.setWipLimit(wipLimit);

        Set<Task> tasks = new HashSet<>();
        IntStream.range(0, assignedTasks).forEach(i -> {
            var task = new Task();
            task.setId(i + 1);
            tasks.add(task);
        });
        user.setTasks(tasks);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }
}
