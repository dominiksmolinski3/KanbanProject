package pl.myproject.kanbanproject2.layout.row;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;

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

class RowControllerHttpTest {
    private RowService rowService;
    private MockMvc mvc;
    private User caller;

    @BeforeEach
    void setUp() {
        rowService = mock(RowService.class);
        caller = new User();
        caller.setId(1);
        var converter = new MappingJackson2HttpMessageConverter(new ObjectMapper().registerModule(new JavaTimeModule()));
        mvc = MockMvcBuilders.standaloneSetup(new RowController(rowService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(converter)
                .build();
    }

    private static RowDto row(Integer id, String name, Integer position) {
        return new RowDto(id, name, position, null, List.of());
    }

    @Test
    @DisplayName("the swimlane list answers 200 and passes the board through")
    void listAnswers200() throws Exception {
        when(rowService.getAllRows(caller, 7)).thenReturn(List.of(row(4, "Urgent", 0)));

        mvc.perform(get("/rows").param("boardId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Urgent"))
                .andExpect(jsonPath("$[0].taskDTO").isArray());
    }

    @Test
    @DisplayName("creating answers 201 with the slim response DTO")
    void createAnswers201() throws Exception {
        when(rowService.createRow(caller, 7, new CreateRowRequest("Later", null, null)))
                .thenReturn(new RowResponseDto(5, "Later", 1, null));

        mvc.perform(post("/rows").param("boardId", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Later\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    @DisplayName("a blank name or a negative WIP limit is a 400 before the service is reached")
    void badCreateIs400() throws Exception {
        mvc.perform(post("/rows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(patch("/rows/4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wipLimit\":-2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(rowService);
    }

    @Test
    @DisplayName("renaming answers 200 with the patched swimlane")
    void patchAnswers200() throws Exception {
        when(rowService.patchRow(caller, new PatchRowRequest("Soon", null, null), 4)).thenReturn(row(4, "Soon", 0));

        mvc.perform(patch("/rows/4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Soon\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Soon"));
    }

    @Test
    @DisplayName("moving and reordering parse their input, and an empty order is a 400")
    void moveAndReorder() throws Exception {
        when(rowService.updateRowPosition(caller, 4, 2)).thenReturn(row(4, "Urgent", 2));
        when(rowService.reorderRows(caller, List.of(5, 4))).thenReturn(List.of(row(5, "b", 0), row(4, "a", 1)));

        mvc.perform(patch("/rows/4/position/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(2));
        mvc.perform(patch("/rows/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":[5,4]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(5));
        mvc.perform(patch("/rows/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("deleting answers 204, and another tenant's swimlane is a 404")
    void deleteRoutes() throws Exception {
        doThrow(new GlobalException(ExceptionIdentifier.ROW_NOT_FOUND)).when(rowService).deleteRow(caller, 99);

        mvc.perform(delete("/rows/4"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(rowService).deleteRow(caller, 4);

        mvc.perform(delete("/rows/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROW_NOT_FOUND"));
    }

    @Test
    @DisplayName("a non-numeric position is a 400, not a 500")
    void nonNumericPositionIs400() throws Exception {
        mvc.perform(patch("/rows/4/position/top"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(rowService);
    }
}
