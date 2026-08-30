package pl.myproject.kanbanproject2.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.config.security.AuthenticationService;
import pl.myproject.kanbanproject2.config.security.PasswordResetService;
import pl.myproject.kanbanproject2.config.security.captcha.CaptchaVerifier;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.auth.CaptchaDto;
import pl.myproject.kanbanproject2.user.auth.LoginUserDto;
import pl.myproject.kanbanproject2.user.auth.RegisterUserDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The service decides what happens; this decides what the caller can see. Both halves have to
 * hold: a uniform status with a body that names the created account leaks just as clearly as a
 * 409 did, so the assertion that matters here is that signup answers 202 with nothing in it.
 */
class AuthenticationControllerHttpTest {

    private AuthenticationService authenticationService;
    private PasswordResetService passwordResetService;
    private CaptchaVerifier captchaVerifier;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        passwordResetService = mock(PasswordResetService.class);
        // A mock rather than a disabled real one: these tests are about what the caller sees, and
        // a stub that throws is how the captcha failure below is provoked without a provider.
        captchaVerifier = mock(CaptchaVerifier.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthenticationController(
                        authenticationService,
                        passwordResetService,
                        captchaVerifier,
                        mock(ClientIpResolver.class)))
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

    @Test
    @DisplayName("a captcha that does not verify stops signup at 400, before the service is reached")
    void signupRefusesAFailedCaptcha() throws Exception {
        doThrow(new GlobalException(ExceptionIdentifier.CAPTCHA_FAILED))
                .when(captchaVerifier).verify(any(), any());

        mvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("new@example.test")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_FAILED"));

        verifyNoInteractions(authenticationService);
    }

    @Test
    @DisplayName("a captcha that does not verify stops login at 400, before any credential is read")
    void loginRefusesAFailedCaptcha() throws Exception {
        doThrow(new GlobalException(ExceptionIdentifier.CAPTCHA_FAILED))
                .when(captchaVerifier).verify(any(), any());

        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.test\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_FAILED"));

        verifyNoInteractions(authenticationService);
    }

    @Test
    @DisplayName("the token the client posted is the one handed to the verifier, not a re-read of the body")
    void passesThePostedTokenThrough() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.test\",\"password\":\"correct-horse\","
                                + "\"captcha\":{\"token\":\"from-the-widget\"}}"))
                .andExpect(status().isOk());

        verify(captchaVerifier).verify(eq(new CaptchaDto("from-the-widget")), any());
    }

    @Test
    @DisplayName("a body with no captcha object still reaches the verifier, which decides what that means")
    void anAbsentCaptchaIsStillTheVerifiersCall() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.test\",\"password\":\"correct-horse\"}"))
                .andExpect(status().isOk());

        // Not skipped here: when verification is on, absent and wrong are the same answer, and
        // that decision lives in one place rather than being half-made at the controller.
        verify(captchaVerifier).verify(eq(null), any());
    }
}
