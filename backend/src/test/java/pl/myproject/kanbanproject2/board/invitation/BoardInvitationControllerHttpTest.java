package pl.myproject.kanbanproject2.board.invitation;

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
import pl.myproject.kanbanproject2.board.BoardRole;
import pl.myproject.kanbanproject2.board.FixedPrincipalResolver;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;

import java.time.LocalDateTime;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BoardInvitationControllerHttpTest {
    private BoardInvitationService invitationService;
    private MockMvc mvc;
    private User caller;

    @BeforeEach
    void setUp() {
        invitationService = mock(BoardInvitationService.class);
        caller = new User();
        caller.setId(1);
        var json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new BoardInvitationController(invitationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    static BoardInvitationDto invitation(Integer id, BoardRole role) {
        return new BoardInvitationDto(id, 7, "Team", "ann@example.com", "Owner",
                InvitationStatus.PENDING, role, LocalDateTime.of(2026, 10, 1, 12, 0));
    }

    @Test
    @DisplayName("inviting answers 201 with the status and role as their enum names")
    void inviteAnswers201() throws Exception {
        when(invitationService.invite(eq(caller), eq(7), any())).thenReturn(invitation(3, BoardRole.VIEWER));

        mvc.perform(post("/boards/7/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ann@example.com\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.role").value("VIEWER"));

        verify(invitationService).invite(caller, 7, new InviteRequest("ann@example.com", BoardRole.VIEWER));
    }

    @Test
    @DisplayName("an invitation without a role reaches the service with a null role")
    void roleIsOptional() throws Exception {
        when(invitationService.invite(eq(caller), eq(7), any())).thenReturn(invitation(3, BoardRole.MEMBER));

        mvc.perform(post("/boards/7/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ann@example.com\"}"))
                .andExpect(status().isCreated());

        verify(invitationService).invite(caller, 7, new InviteRequest("ann@example.com"));
    }

    @Test
    @DisplayName("a malformed address is a 400 before the service is reached")
    void badEmailIs400() throws Exception {
        mvc.perform(post("/boards/7/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-address\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(invitationService);
    }

    @Test
    @DisplayName("a role the enum does not know is a 400, so OWNER cannot be granted by invitation")
    void unknownRoleIs400() throws Exception {
        mvc.perform(post("/boards/7/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ann@example.com\",\"role\":\"OWNER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(invitationService);
    }

    @Test
    @DisplayName("inviting someone already on the board is a 400 carrying ALREADY_BOARD_MEMBER")
    void existingMemberIs400() throws Exception {
        when(invitationService.invite(eq(caller), eq(7), any()))
                .thenThrow(new GlobalException(ExceptionIdentifier.ALREADY_BOARD_MEMBER));

        mvc.perform(post("/boards/7/invitations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ann@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALREADY_BOARD_MEMBER"));
    }

    @Test
    @DisplayName("a member who is not the owner is refused with a 403 carrying NOT_BOARD_OWNER")
    void nonOwnerIs403() throws Exception {
        when(invitationService.pendingFor(caller, 7)).thenThrow(new GlobalException(ExceptionIdentifier.NOT_BOARD_OWNER));

        mvc.perform(get("/boards/7/invitations"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_BOARD_OWNER"));
    }

    @Test
    @DisplayName("the pending list answers 200")
    void pendingAnswers200() throws Exception {
        when(invitationService.pendingFor(caller, 7)).thenReturn(List.of(invitation(3, BoardRole.MEMBER)));

        mvc.perform(get("/boards/7/invitations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("ann@example.com"))
                .andExpect(jsonPath("$[0].boardName").value("Team"));
    }

    @Test
    @DisplayName("revoking answers 204 with both ids reaching the service")
    void revokeAnswers204() throws Exception {
        mvc.perform(delete("/boards/7/invitations/3"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(invitationService).revoke(caller, 7, 3);
    }

    @Test
    @DisplayName("revoking a missing invitation is a 404, not a silent 204")
    void revokingMissingIs404() throws Exception {
        doThrow(new GlobalException(ExceptionIdentifier.INVITATION_NOT_FOUND))
                .when(invitationService).revoke(caller, 7, 404);

        mvc.perform(delete("/boards/7/invitations/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
    }
}
