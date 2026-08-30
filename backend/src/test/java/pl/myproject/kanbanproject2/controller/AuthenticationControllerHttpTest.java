package pl.myproject.kanbanproject2.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.config.security.AuthenticationService;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.auth.RegisterUserDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The service decides what happens; this decides what the caller can see. Both halves have to
 * hold: a uniform status with a body that names the created account leaks just as clearly as a
 * 409 did, so the assertion that matters here is that signup answers 202 with nothing in it.
 */
class AuthenticationControllerHttpTest {

    private AuthenticationService authenticationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthenticationController(authenticationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String body(String email) {
        return "{\"username\":\"someone\",\"email\":\"" + email + "\",\"password\":\"correct-horse\"}";
    }

    @Test
    @DisplayName("signup answers 202 with an empty body")
    void signupAnswers202WithNoBody() throws Exception {
        doNothing().when(authenticationService).signup(any(RegisterUserDto.class));

        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("new@example.test")))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("a new address and one that already has an account produce byte-identical responses")
    void bothSignupsAreIndistinguishable() throws Exception {
        doNothing().when(authenticationService).signup(any(RegisterUserDto.class));

        MvcResult fresh = mvc.perform(post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("new@example.test"))).andReturn();

        MvcResult taken = mvc.perform(post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("taken@example.test"))).andReturn();

        assertThat(fresh.getResponse().getStatus()).isEqualTo(taken.getResponse().getStatus());
        assertThat(fresh.getResponse().getContentAsString())
                .isEqualTo(taken.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("resend answers 202 with an empty body as well")
    void resendAnswers202() throws Exception {
        mvc.perform(post("/auth/resend").param("email", "anyone@example.test"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(authenticationService).resendVerificationCode(eq("anyone@example.test"));
    }

    @Test
    @DisplayName("bean validation still rejects a malformed body before the service is reached")
    void validationStillApplies() throws Exception {
        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-an-email")))
                .andExpect(status().isBadRequest());

        verify(authenticationService, org.mockito.Mockito.never()).signup(any());
    }
}
