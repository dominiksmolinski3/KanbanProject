package pl.myproject.kanbanproject2.board.invitation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.board.BoardDto;
import pl.myproject.kanbanproject2.board.BoardRole;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MyInvitationsControllerHttpTest {
    private BoardInvitationService invitationService;
    private MockMvc mvc;
    private User caller;

    @BeforeEach
    void setUp() {
        invitationService = mock(BoardInvitationService.class);
        caller = new User();
        caller.setId(2);
        var json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new MyInvitationsController(invitationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    @Test
    @DisplayName("the caller's own invitations answer 200")
    void mineAnswers200() throws Exception {
        when(invitationService.myInvitations(caller))
                .thenReturn(List.of(BoardInvitationControllerHttpTest.invitation(3, BoardRole.MEMBER)));

        mvc.perform(get("/invitations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(3))
                .andExpect(jsonPath("$[0].boardId").value(7));
    }

    @Test
    @DisplayName("accepting answers 200 with the board just joined")
    void acceptAnswersTheBoard() throws Exception {
        when(invitationService.accept(caller, 3)).thenReturn(new BoardDto(7, "Team", 1, false, List.of(), BoardRole.MEMBER));

        mvc.perform(post("/invitations/3/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.owned").value(false))
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    @Test
    @DisplayName("accepting an invitation addressed to someone else is a 404, never a 403")
    void foreignInvitationIs404() throws Exception {
        when(invitationService.accept(caller, 9)).thenThrow(new GlobalException(ExceptionIdentifier.INVITATION_NOT_FOUND));

        mvc.perform(post("/invitations/9/accept"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("declining answers 204 with no body")
    void declineAnswers204() throws Exception {
        mvc.perform(post("/invitations/3/decline"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(invitationService).decline(caller, 3);
    }

    @Test
    @DisplayName("declining a missing invitation is a 404")
    void decliningMissingIs404() throws Exception {
        doThrow(new GlobalException(ExceptionIdentifier.INVITATION_NOT_FOUND)).when(invitationService).decline(caller, 404);

        mvc.perform(post("/invitations/404/decline"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("accepting is POST only: a GET, which a link preview could fire, is a 405")
    void acceptIsPostOnly() throws Exception {
        mvc.perform(get("/invitations/3/accept"))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(invitationService);
    }
}
