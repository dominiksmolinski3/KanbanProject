package pl.myproject.kanbanproject2.chat;

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

class ChatHistoryControllerHttpTest {
    private ChatHistoryService historyService;
    private MockMvc mvc;
    private User caller;

    @BeforeEach
    void setUp() {
        historyService = mock(ChatHistoryService.class);
        caller = new User();
        caller.setId(1);
        var json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new ChatHistoryController(historyService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    private static ChatMessageResults page(ChatMessageDto... messages) {
        return new ChatMessageResults(List.of(messages), 0, 25, messages.length, 1);
    }

    @Test
    @DisplayName("the board history answers 200 with the envelope and the message type as its enum name")
    void boardHistoryAnswers200() throws Exception {
        var hello = new ChatMessageDto(1, MessageType.CHAT, "hello", "ann", 7, null, LocalDateTime.of(2026, 10, 1, 12, 0));
        when(historyService.boardHistory(caller, 7, 1, 50)).thenReturn(page(hello));

        mvc.perform(get("/chat").param("boardId", "7").param("page", "1").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].type").value("CHAT"))
                .andExpect(jsonPath("$.messages[0].content").value("hello"))
                .andExpect(jsonPath("$.messages[0].boardId").value(7));
    }

    @Test
    @DisplayName("the direct history passes the peer through, with no paging params as nulls")
    void directHistoryPassesThePeer() throws Exception {
        var whisper = new ChatMessageDto(2, MessageType.PRIVATE, "psst", "ann", null, "bob", LocalDateTime.of(2026, 10, 1, 12, 0));
        when(historyService.directHistory(caller, "bob", null, null)).thenReturn(page(whisper));

        mvc.perform(get("/chat/direct").param("with", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].recipientId").value("bob"))
                .andExpect(jsonPath("$.messages[0].type").value("PRIVATE"));
    }

    @Test
    @DisplayName("the direct history without a peer is a 400 carrying MISSING_PARAMETER")
    void directHistoryNeedsAPeer() throws Exception {
        mvc.perform(get("/chat/direct"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));

        verifyNoInteractions(historyService);
    }

    @Test
    @DisplayName("an oversized page reaches the service unclamped and comes back as its 400")
    void oversizedPageIs400() throws Exception {
        when(historyService.boardHistory(caller, null, null, 101))
                .thenThrow(new GlobalException(ExceptionIdentifier.INVALID_CHAT_REQUEST, "size must be between 1 and 100"));

        mvc.perform(get("/chat").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CHAT_REQUEST"));
    }

    @Test
    @DisplayName("a non-numeric page is a 400 before the service is reached")
    void nonNumericPageIs400() throws Exception {
        mvc.perform(get("/chat/direct").param("with", "bob").param("page", "first"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verifyNoInteractions(historyService);
    }

    @Test
    @DisplayName("another tenant's board is a 404, never a 403")
    void foreignBoardIs404() throws Exception {
        when(historyService.boardHistory(caller, 99, null, null)).thenThrow(new GlobalException(ExceptionIdentifier.BOARD_NOT_FOUND));

        mvc.perform(get("/chat").param("boardId", "99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));
    }
}
