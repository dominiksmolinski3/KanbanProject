package pl.myproject.kanbanproject2.task.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.board.FixedPrincipalResolver;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskCommentControllerHttpTest {
    private TaskCommentService commentService;
    private MockMvc mvc;
    private ObjectMapper json;
    private User caller;

    @BeforeEach
    void setUp() {
        commentService = mock(TaskCommentService.class);
        caller = new User();
        caller.setId(1);
        json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new TaskCommentController(commentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    private static TaskCommentDto comment(Long id, String body) {
        return new TaskCommentDto(id, 5, body, 1, "Ann", Instant.parse("2026-10-01T12:00:00Z"), null);
    }

    private String body(String text) throws Exception {
        return json.writeValueAsString(Map.of("body", text));
    }

    @Test
    @DisplayName("the thread answers 200 with the page envelope, and no paging params reach the service as null")
    void threadAnswersTheEnvelope() throws Exception {
        when(commentService.thread(caller, 5, null, null))
                .thenReturn(new TaskCommentResults(List.of(comment(11L, "first")), 0, 25, 1, 1));

        mvc.perform(get("/tasks/5/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comments[0].id").value(11))
                .andExpect(jsonPath("$.comments[0].authorName").value("Ann"))
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalEntries").value(1));
    }

    @Test
    @DisplayName("an oversized page reaches the service unclamped and comes back as its 400")
    void oversizedPageIs400() throws Exception {
        when(commentService.thread(caller, 5, 0, 101))
                .thenThrow(new GlobalException(ExceptionIdentifier.INVALID_COMMENT_REQUEST, "size must be between 1 and 100"));

        mvc.perform(get("/tasks/5/comments").param("page", "0").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMMENT_REQUEST"));
    }

    @Test
    @DisplayName("a non-numeric page size is a 400 before the service is reached")
    void nonNumericSizeIs400() throws Exception {
        mvc.perform(get("/tasks/5/comments").param("size", "lots"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(commentService);
    }

    @Test
    @DisplayName("adding answers 201")
    void addAnswers201() throws Exception {
        when(commentService.add(eq(caller), eq(5), any())).thenReturn(comment(12L, "hello"));

        mvc.perform(post("/tasks/5/comments").contentType(MediaType.APPLICATION_JSON).content(body("hello")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("hello"));

        verify(commentService).add(caller, 5, new TaskCommentRequest("hello"));
    }

    @Test
    @DisplayName("a blank comment, or one over the cap, is a 400 before the service; one at the cap is not")
    void bodyIsValidated() throws Exception {
        String atCap = "c".repeat(TaskCommentRequest.MAX_LENGTH);

        mvc.perform(post("/tasks/5/comments").contentType(MediaType.APPLICATION_JSON).content(body("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(patch("/tasks/5/comments/12").contentType(MediaType.APPLICATION_JSON).content(body(atCap + "c")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(commentService);

        when(commentService.add(eq(caller), eq(5), any())).thenReturn(comment(12L, atCap));
        mvc.perform(post("/tasks/5/comments").contentType(MediaType.APPLICATION_JSON).content(body(atCap)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("editing answers 200 and passes the comment id through as a long")
    void editAnswers200() throws Exception {
        when(commentService.edit(caller, 5, 12L, new TaskCommentRequest("fixed"))).thenReturn(comment(12L, "fixed"));

        mvc.perform(patch("/tasks/5/comments/12").contentType(MediaType.APPLICATION_JSON).content(body("fixed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("fixed"));
    }

    @Test
    @DisplayName("editing someone else's comment is a 403 carrying NOT_COMMENT_AUTHOR")
    void foreignCommentIs403() throws Exception {
        when(commentService.edit(eq(caller), eq(5), eq(13L), any()))
                .thenThrow(new GlobalException(ExceptionIdentifier.NOT_COMMENT_AUTHOR));

        mvc.perform(patch("/tasks/5/comments/13").contentType(MediaType.APPLICATION_JSON).content(body("mine now")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_COMMENT_AUTHOR"));
    }

    @Test
    @DisplayName("deleting answers 204, and a missing comment is a 404")
    void deleteRoutes() throws Exception {
        doThrow(new GlobalException(ExceptionIdentifier.COMMENT_NOT_FOUND)).when(commentService).delete(caller, 5, 404L);

        mvc.perform(delete("/tasks/5/comments/12"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(commentService).delete(caller, 5, 12L);

        mvc.perform(delete("/tasks/5/comments/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("commenting on another tenant's task is a 404, never a 403")
    void foreignTaskIs404() throws Exception {
        when(commentService.thread(caller, 99, null, null)).thenThrow(new GlobalException(ExceptionIdentifier.TASK_NOT_FOUND));

        mvc.perform(get("/tasks/99/comments"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }
}
