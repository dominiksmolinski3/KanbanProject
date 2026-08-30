package pl.myproject.kanbanproject2.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.board.FixedPrincipalResolver;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The service-layer tests assert the service contract; nothing until now asserted the HTTP one -
 * the status a controller answers with, the shape it serialises, or that a
 * {@link GlobalException} reaches the client as the status its identifier declares.
 *
 * <p>A standalone MockMvc setup is used rather than {@code @WebMvcTest}: the slice would pull in
 * the security chain and the rate limiter, which have their own suites, and the {@code /api}
 * prefix is applied centrally and already guarded by {@code ApiPathPrefixTest}. Paths here are
 * therefore the controller's own mapping, without the prefix.
 */
class TaskControllerHttpTest {

    private TaskService taskService;
    private MockMvc mvc;
    private ObjectMapper json;
    private User caller;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        caller = new User();
        caller.setId(1);
        json = new ObjectMapper()
                .registerModule(new JsonNullableModule())
                .registerModule(new JavaTimeModule());

        var converter = new MappingJackson2HttpMessageConverter(json);
        mvc = MockMvcBuilders.standaloneSetup(new TaskController(taskService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(converter)
                .build();
    }

    private static TaskDto dto(Integer id, String title) {
        return new TaskDto(id, title, 1, 2, 3, Set.of(4), Set.of("bug"),
                false, "described", null, Set.of(), null, false, false);
    }

    @Test
    @DisplayName("the listing answers 200 with the mapped DTOs")
    void listAnswers200() throws Exception {
        when(taskService.getAllTasks(caller, null)).thenReturn(List.of(dto(1, "first")));

        mvc.perform(get("/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].title").value("first"))
                .andExpect(jsonPath("$[0].columnId").value(2))
                .andExpect(jsonPath("$[0].dailyFocus").value(false));
    }

    @Test
    @DisplayName("creating answers 201, not 200")
    void createAnswers201() throws Exception {
        when(taskService.addTask(eq(caller), eq(null), any())).thenReturn(dto(1, "new"));

        mvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"new\",\"column\":{\"id\":2}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("new"));
    }

    @Test
    @DisplayName("a blank title is rejected as 400 by bean validation before the service is reached")
    void blankTitleIs400() throws Exception {
        mvc.perform(post("/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("deleting answers 204 with no body")
    void deleteAnswers204() throws Exception {
        mvc.perform(delete("/tasks/1"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(taskService).deleteTask(caller, 1);
    }

    @Test
    @DisplayName("a missing task reaches the client as the 404 its identifier declares")
    void missingTaskIs404() throws Exception {
        when(taskService.getTaskById(caller, 404))
                .thenThrow(new GlobalException(ExceptionIdentifier.TASK_NOT_FOUND));

        mvc.perform(get("/tasks/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    @DisplayName("deleting a missing task is a 404, not a silent 204")
    void deletingMissingTaskIs404() throws Exception {
        doThrow(new GlobalException(ExceptionIdentifier.TASK_NOT_FOUND))
                .when(taskService).deleteTask(caller, 404);

        mvc.perform(delete("/tasks/404"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the parent-task refusal is a 400 carrying PARENT_TASK_NOT_COMPLETED")
    void completionRefusalIsTyped() throws Exception {
        when(taskService.updateTaskCompletion(caller, 1, true))
                .thenThrow(new GlobalException(ExceptionIdentifier.PARENT_TASK_NOT_COMPLETED));

        mvc.perform(patch("/tasks/1/complete/true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARENT_TASK_NOT_COMPLETED"));
    }

    @Test
    @DisplayName("the per-user WIP refusal is a 400 carrying USER_WIP_LIMIT_EXCEEDED")
    void wipRefusalIsTyped() throws Exception {
        when(taskService.assignUserToTask(caller, 1, 5))
                .thenThrow(new GlobalException(ExceptionIdentifier.USER_WIP_LIMIT_EXCEEDED));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/tasks/1/user/5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("USER_WIP_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("a cyclic parent link is a 400, not a stack overflow")
    void cyclicParentIs400() throws Exception {
        when(taskService.assignParentTask(caller, 1, 2))
                .thenThrow(new GlobalException(ExceptionIdentifier.CYCLIC_TASK_DEPENDENCY));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/tasks/1/parent/2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CYCLIC_TASK_DEPENDENCY"));
    }

    @Test
    @DisplayName("the label vocabulary serialises as a bare JSON array")
    void labelsAnswerAnArray() throws Exception {
        when(taskService.getAllLabels(caller, null)).thenReturn(Set.of("bug"));

        mvc.perform(get("/tasks/get/all/labels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("bug"));
    }

    @Test
    @DisplayName("can-complete answers a bare boolean")
    void canCompleteAnswersABoolean() throws Exception {
        when(taskService.canTaskBeCompleted(caller, 1)).thenReturn(false);

        mvc.perform(get("/tasks/1/can-complete"))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));
    }

    @Test
    @DisplayName("the daily-focus listing and toggle reach the service with the parsed status")
    void dailyFocusRoutes() throws Exception {
        when(taskService.getDailyFocusTasks(caller, null)).thenReturn(List.of(dto(1, "starred")));
        when(taskService.setDailyFocus(eq(caller), eq(1), eq(true))).thenReturn(dto(1, "starred"));

        mvc.perform(get("/tasks/daily-focus")).andExpect(status().isOk());
        mvc.perform(patch("/tasks/1/daily-focus/true")).andExpect(status().isOk());

        verify(taskService).setDailyFocus(caller, 1, true);
    }

    @Test
    @DisplayName("a patch that mentions only description leaves the rest undefined at the service")
    void patchPassesTheTriState() throws Exception {
        when(taskService.patchTask(eq(caller), eq(1), any())).thenReturn(dto(1, "unchanged"));

        mvc.perform(patch("/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"only this\"}"))
                .andExpect(status().isOk());

        verify(taskService).patchTask(eq(caller), eq(1), any(PatchTaskRequest.class));
    }
}
