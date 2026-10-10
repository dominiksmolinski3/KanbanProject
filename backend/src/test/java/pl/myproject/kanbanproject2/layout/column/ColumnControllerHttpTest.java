package pl.myproject.kanbanproject2.layout.column;

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

class ColumnControllerHttpTest {
    private ColumnService columnService;
    private MockMvc mvc;
    private User caller;

    @BeforeEach
    void setUp() {
        columnService = mock(ColumnService.class);
        caller = new User();
        caller.setId(1);
        var converter = new MappingJackson2HttpMessageConverter(new ObjectMapper().registerModule(new JavaTimeModule()));
        mvc = MockMvcBuilders.standaloneSetup(new ColumnController(columnService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(converter)
                .build();
    }

    private static ColumnDto column(Integer id, String name, Integer position) {
        return new ColumnDto(id, name, position, 3, List.of());
    }

    @Test
    @DisplayName("the column list answers 200 and passes the board through")
    void listAnswers200() throws Exception {
        when(columnService.getAllColumns(caller, 7)).thenReturn(List.of(column(2, "Doing", 1)));

        mvc.perform(get("/columns").param("boardId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Doing"))
                .andExpect(jsonPath("$[0].wipLimit").value(3))
                .andExpect(jsonPath("$[0].taskDTO").isArray());
    }

    @Test
    @DisplayName("creating answers 201 with the slim response DTO")
    void createAnswers201() throws Exception {
        when(columnService.addNewColumn(caller, 7, new CreateColumnRequest("Review", null, 2)))
                .thenReturn(new ColumnResponseDto(9, "Review", 3, 2));

        mvc.perform(post("/columns").param("boardId", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Review\",\"wipLimit\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.position").value(3));
    }

    @Test
    @DisplayName("a blank name or a negative WIP limit is a 400 before the service is reached")
    void badCreateIs400() throws Exception {
        mvc.perform(post("/columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/columns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Review\",\"wipLimit\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(columnService);
    }

    @Test
    @DisplayName("a patch may leave the name out, but may not blank it")
    void patchNameRules() throws Exception {
        when(columnService.patchColumn(caller, new PatchColumnRequest(null, null, 5), 2)).thenReturn(column(2, "Doing", 1));

        mvc.perform(patch("/columns/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wipLimit\":5}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/columns/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(columnService).patchColumn(caller, new PatchColumnRequest(null, null, 5), 2);
    }

    @Test
    @DisplayName("moving a column parses both path ids")
    void moveParsesThePath() throws Exception {
        when(columnService.updateColumnPosition(caller, 2, 0)).thenReturn(column(2, "Doing", 0));

        mvc.perform(patch("/columns/2/position/0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(0));
    }

    @Test
    @DisplayName("reordering passes the ids in order, and an empty order is a 400")
    void reorderRoutes() throws Exception {
        when(columnService.reorderColumns(caller, List.of(3, 1, 2)))
                .thenReturn(List.of(column(3, "c", 0), column(1, "a", 1), column(2, "b", 2)));

        mvc.perform(patch("/columns/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":[3,1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(3));
        mvc.perform(patch("/columns/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("an order the service cannot apply is a 400 carrying INVALID_REORDER")
    void refusedOrderIs400() throws Exception {
        when(columnService.reorderColumns(eq(caller), any()))
                .thenThrow(new GlobalException(ExceptionIdentifier.INVALID_REORDER));

        mvc.perform(patch("/columns/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderedIds\":[1,1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REORDER"));
    }

    @Test
    @DisplayName("deleting answers 204, and another tenant's column is a 404")
    void deleteRoutes() throws Exception {
        doThrow(new GlobalException(ExceptionIdentifier.COLUMN_NOT_FOUND)).when(columnService).deleteColumn(caller, 99);

        mvc.perform(delete("/columns/2"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(columnService).deleteColumn(caller, 2);

        mvc.perform(delete("/columns/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COLUMN_NOT_FOUND"));
    }

    @Test
    @DisplayName("a viewer changing a column is a 403 carrying VIEWER_READ_ONLY")
    void viewerIs403() throws Exception {
        when(columnService.updateColumnPosition(caller, 2, 1)).thenThrow(new GlobalException(ExceptionIdentifier.VIEWER_READ_ONLY));

        mvc.perform(patch("/columns/2/position/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VIEWER_READ_ONLY"));
    }
}
