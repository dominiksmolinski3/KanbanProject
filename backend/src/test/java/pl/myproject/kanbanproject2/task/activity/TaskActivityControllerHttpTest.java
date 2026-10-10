package pl.myproject.kanbanproject2.task.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.board.FixedPrincipalResolver;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskActivityControllerHttpTest {
    private TaskActivityService activityService;
    private MockMvc mvc;
    private User caller;

    @BeforeEach
    void setUp() {
        activityService = mock(TaskActivityService.class);
        caller = new User();
        caller.setId(1);
        var json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new TaskActivityController(activityService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    @Test
    @DisplayName("the feed answers 200 with the envelope and the activity type as its enum name")
    void feedAnswers200() throws Exception {
        var moved = new TaskActivityDto(1, 5, "Fix login", 1, "Ann", TaskActivityType.MOVED,
                "To do -> Doing", LocalDateTime.of(2026, 10, 1, 12, 0));
        when(activityService.feed(caller, 7, 2, 10)).thenReturn(new TaskActivityResults(List.of(moved), 2, 10, 21, 3));

        mvc.perform(get("/activity").param("boardId", "7").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activities[0].type").value("MOVED"))
                .andExpect(jsonPath("$.activities[0].taskTitle").value("Fix login"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    @Test
    @DisplayName("no params reach the service as nulls, so the defaults live in one place")
    void defaultsAreTheServices() throws Exception {
        when(activityService.feed(caller, null, null, null)).thenReturn(new TaskActivityResults(List.of(), 0, 25, 0, 0));

        mvc.perform(get("/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(25));
    }

    @Test
    @DisplayName("an oversized page reaches the service unclamped and comes back as its 400")
    void oversizedPageIs400() throws Exception {
        when(activityService.feed(caller, null, null, 101))
                .thenThrow(new GlobalException(ExceptionIdentifier.INVALID_ACTIVITY_REQUEST, "size must be between 1 and 100"));

        mvc.perform(get("/activity").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACTIVITY_REQUEST"));
    }

    @Test
    @DisplayName("a non-numeric board id is a 400 before the service is reached")
    void nonNumericBoardIs400() throws Exception {
        mvc.perform(get("/activity").param("boardId", "seven"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(activityService);
    }

    @Test
    @DisplayName("another tenant's board is a 404, never a 403")
    void foreignBoardIs404() throws Exception {
        when(activityService.feed(caller, 99, null, null)).thenThrow(new GlobalException(ExceptionIdentifier.BOARD_NOT_FOUND));

        mvc.perform(get("/activity").param("boardId", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));
    }
}
