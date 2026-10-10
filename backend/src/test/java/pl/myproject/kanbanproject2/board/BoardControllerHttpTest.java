package pl.myproject.kanbanproject2.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

class BoardControllerHttpTest {
    private BoardService boardService;
    private MockMvc mvc;
    private User caller;

    @BeforeEach
    void setUp() {
        boardService = mock(BoardService.class);
        caller = new User();
        caller.setId(1);
        var converter = new MappingJackson2HttpMessageConverter(new ObjectMapper().registerModule(new JavaTimeModule()));
        mvc = MockMvcBuilders.standaloneSetup(new BoardController(boardService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(converter)
                .build();
    }

    private static BoardDto board(Integer id, String name) {
        return new BoardDto(id, name, 1, true, List.of(), null);
    }

    @Test
    @DisplayName("the board list answers 200 with the fields the switcher reads")
    void listAnswers200() throws Exception {
        when(boardService.myBoards(caller)).thenReturn(List.of(board(7, "Mine")));

        mvc.perform(get("/boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].name").value("Mine"))
                .andExpect(jsonPath("$[0].ownerId").value(1))
                .andExpect(jsonPath("$[0].owned").value(true));
    }

    @Test
    @DisplayName("the current board answers 200")
    void currentAnswers200() throws Exception {
        when(boardService.currentBoard(caller)).thenReturn(board(7, "Mine"));

        mvc.perform(get("/boards/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    @DisplayName("creating answers 201 and passes the name through")
    void createAnswers201() throws Exception {
        when(boardService.createBoard(eq(caller), any())).thenReturn(board(8, "New"));

        mvc.perform(post("/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("New"));

        verify(boardService).createBoard(caller, new CreateBoardRequest("New"));
    }

    @Test
    @DisplayName("a blank or overlong board name is a 400 before the service is reached")
    void badNameIs400() throws Exception {
        mvc.perform(post("/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(patch("/boards/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "n".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(boardService);
    }

    @Test
    @DisplayName("another tenant's board is a 404, never a 403")
    void foreignBoardIs404() throws Exception {
        when(boardService.getBoard(caller, 99)).thenThrow(new GlobalException(ExceptionIdentifier.BOARD_NOT_FOUND));

        mvc.perform(get("/boards/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));
    }

    @Test
    @DisplayName("renaming answers 200 with the renamed board")
    void renameAnswers200() throws Exception {
        when(boardService.renameBoard(caller, 7, new PatchBoardRequest("Renamed"))).thenReturn(board(7, "Renamed"));

        mvc.perform(patch("/boards/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    @DisplayName("deleting answers 204 with no body")
    void deleteAnswers204() throws Exception {
        mvc.perform(delete("/boards/7"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(boardService).deleteBoard(caller, 7);
    }

    @Test
    @DisplayName("a member deleting a board they do not own is a 403 carrying NOT_BOARD_OWNER")
    void nonOwnerDeleteIs403() throws Exception {
        doThrow(new GlobalException(ExceptionIdentifier.NOT_BOARD_OWNER)).when(boardService).deleteBoard(caller, 7);

        mvc.perform(delete("/boards/7"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_BOARD_OWNER"));
    }

    @Test
    @DisplayName("removing a member answers 200 with the board, and the owner cannot be removed")
    void removeMemberRoutes() throws Exception {
        when(boardService.removeMember(caller, 7, 5)).thenReturn(board(7, "Mine"));
        when(boardService.removeMember(caller, 7, 1))
                .thenThrow(new GlobalException(ExceptionIdentifier.CANNOT_REMOVE_BOARD_OWNER));

        mvc.perform(delete("/boards/7/members/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
        mvc.perform(delete("/boards/7/members/1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_REMOVE_BOARD_OWNER"));
    }

    @Test
    @DisplayName("a non-numeric board id is a 400, not a 500")
    void nonNumericIdIs400() throws Exception {
        mvc.perform(get("/boards/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verify(boardService, never()).getBoard(any(), any());
    }
}
