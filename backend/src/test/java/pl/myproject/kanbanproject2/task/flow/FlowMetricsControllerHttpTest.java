package pl.myproject.kanbanproject2.task.flow;

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

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FlowMetricsControllerHttpTest {
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    private FlowMetricsService flowMetricsService;
    private MockMvc mvc;
    private User caller;

    @BeforeEach
    void setUp() {
        flowMetricsService = mock(FlowMetricsService.class);
        caller = new User();
        caller.setId(1);
        var json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new FlowMetricsController(flowMetricsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    @Test
    @DisplayName("the metrics answer 200, with ISO dates and column ids parsed on the way in")
    void metricsAnswer200() throws Exception {
        when(flowMetricsService.metrics(caller, 7, FROM, TO, 2, 4)).thenReturn(new FlowMetricsDto(
                7, FROM, TO, 2, 4, null, null,
                List.of(new FlowMetricsDto.Column(2, "Doing", 1)), List.of(), null, List.of(), List.of()));

        mvc.perform(get("/flow")
                        .param("boardId", "7").param("from", "2026-09-01").param("to", "2026-09-30")
                        .param("start", "2").param("done", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boardId").value(7))
                .andExpect(jsonPath("$.from").value("2026-09-01"))
                .andExpect(jsonPath("$.columns[0].name").value("Doing"))
                .andExpect(jsonPath("$.doneColumnId").value(4));
    }

    @Test
    @DisplayName("a date that is not ISO is a 400 before the service is reached")
    void nonIsoDateIs400() throws Exception {
        mvc.perform(get("/flow").param("from", "01/09/2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(flowMetricsService);
    }

    @Test
    @DisplayName("a range the service refuses comes back as its 400")
    void refusedRangeIs400() throws Exception {
        when(flowMetricsService.metrics(caller, null, TO, FROM, null, null))
                .thenThrow(new GlobalException(ExceptionIdentifier.INVALID_FLOW_REQUEST, "from must not be after to"));

        mvc.perform(get("/flow").param("from", "2026-09-30").param("to", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FLOW_REQUEST"));
    }

    @Test
    @DisplayName("defining the flow answers 200 and passes both columns through")
    void defineAnswers200() throws Exception {
        when(flowMetricsService.define(caller, 7, new FlowDefinitionRequest(2, 4)))
                .thenReturn(new FlowDefinitionDto(7, 2, 4));

        mvc.perform(put("/flow/definition").param("boardId", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startColumnId\":2,\"doneColumnId\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startColumnId").value(2))
                .andExpect(jsonPath("$.doneColumnId").value(4));
    }

    @Test
    @DisplayName("a body that is not JSON is a 400 carrying MALFORMED_REQUEST")
    void malformedBodyIs400() throws Exception {
        mvc.perform(put("/flow/definition")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startColumnId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(flowMetricsService);
    }

    @Test
    @DisplayName("a viewer defining the flow is a 403 carrying VIEWER_READ_ONLY")
    void viewerIs403() throws Exception {
        when(flowMetricsService.define(eq(caller), eq(7), any()))
                .thenThrow(new GlobalException(ExceptionIdentifier.VIEWER_READ_ONLY));

        mvc.perform(put("/flow/definition").param("boardId", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startColumnId\":2,\"doneColumnId\":4}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VIEWER_READ_ONLY"));
    }
}
