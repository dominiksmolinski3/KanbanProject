package pl.myproject.kanbanproject2.task.history;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.board.FixedPrincipalResolver;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.task.TaskService;
import pl.myproject.kanbanproject2.user.User;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one route the history package exposes.
 *
 * <p>Standalone MockMvc rather than {@code @WebMvcTest}, matching the other controller slices here:
 * the full slice would pull in the security chain and the rate limiter, which have their own
 * suites. What this adds over the service tests is that the caller reaches the service - a handler
 * that dropped {@code currentUser} would compile, and would hand one account another's history.
 */
class TaskColumnHistoryControllerHttpTest {

    private static final User CALLER = TenancyFixtures.tenant().caller();

    private TaskService taskService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TaskColumnHistoryController(taskService))
                .setCustomArgumentResolvers(new FixedPrincipalResolver(CALLER))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("the history of a task comes back as the moves it records")
    void answersWithTheMoves() throws Exception {
        when(taskService.getTaskColumnHistoryDTOs(CALLER, 4)).thenReturn(List.of(
                new TaskColumnHistoryDto(1, 4, "Write the migration", 2, "Doing",
                        LocalDateTime.of(2026, 8, 31, 12, 0))));

        mvc.perform(get("/tasks/4/column-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].columnName").value("Doing"))
                .andExpect(jsonPath("$[0].taskTitle").value("Write the migration"));
    }

    @Test
    @DisplayName("the caller is passed to the service, not left for it to guess")
    void theCallerReachesTheService() throws Exception {
        when(taskService.getTaskColumnHistoryDTOs(CALLER, 4)).thenReturn(List.of());

        mvc.perform(get("/tasks/4/column-history")).andExpect(status().isOk());

        verify(taskService).getTaskColumnHistoryDTOs(eq(CALLER), eq(4));
    }

    @Test
    @DisplayName("a task on another board answers 404, the same as one that does not exist")
    void aTaskOnAnotherBoardIsNotFound() throws Exception {
        when(taskService.getTaskColumnHistoryDTOs(CALLER, 9))
                .thenThrow(new GlobalException(ExceptionIdentifier.TASK_NOT_FOUND));

        mvc.perform(get("/tasks/9/column-history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }
}
