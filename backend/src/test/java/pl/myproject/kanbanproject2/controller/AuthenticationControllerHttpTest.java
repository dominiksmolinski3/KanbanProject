package pl.myproject.kanbanproject2.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import pl.myproject.kanbanproject2.config.security.AuthenticationService;
import pl.myproject.kanbanproject2.config.security.LoginResponse;
import pl.myproject.kanbanproject2.config.security.PasswordResetService;
import pl.myproject.kanbanproject2.config.security.captcha.CaptchaVerifier;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.auth.ActiveDeviceDto;
import pl.myproject.kanbanproject2.user.auth.CaptchaDto;
import pl.myproject.kanbanproject2.user.auth.LoginUserDto;
import pl.myproject.kanbanproject2.user.auth.RegisterUserDto;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    private User caller;

    /** Stands in for {@code @AuthenticationPrincipal}, which the standalone setup does not wire. */
    private class PrincipalResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return User.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            return caller;
        }
    }

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        passwordResetService = mock(PasswordResetService.class);
        // A mock rather than a disabled real one: these tests are about what the caller sees, and
        // a stub that throws is how the captcha failure below is provoked without a provider.
        captchaVerifier = mock(CaptchaVerifier.class);
        caller = new User("someone", "someone@example.test", "hashed");
        caller.setId(7);
        mvc = MockMvcBuilders.standaloneSetup(new AuthenticationController(
                        authenticationService,
                        passwordResetService,
                        captchaVerifier,
                        mock(ClientIpResolver.class)))
                .setCustomArgumentResolvers(new PrincipalResolver())
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
    @DisplayName("verify answers 200 with the session, so the client has somewhere to go")
    void verifyAnswersWithASession() throws Exception {
        when(authenticationService.verifyUser(any(), any()))
                .thenReturn(new LoginResponse("signed-access-token", 900000L, "a-refresh-token", 2592000000L, 12L));

        mvc.perform(post("/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.test\",\"verificationCode\":\"111111\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("signed-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("a-refresh-token"));
    }

    @Test
    @DisplayName("a code that does not check out is still a 400 with no session in it")
    void aBadCodeCarriesNoSession() throws Exception {
        when(authenticationService.verifyUser(any(), any()))
                .thenThrow(new GlobalException(ExceptionIdentifier.INVALID_VERIFICATION_CODE));

        mvc.perform(post("/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.test\",\"verificationCode\":\"222222\"}"))
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"))
                .andExpect(jsonPath("$.token").doesNotExist());
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

    @Test
    @DisplayName("refresh hands back a new pair and never asks the captcha verifier anything")
    void refreshAnswersWithANewPair() throws Exception {
        when(authenticationService.refresh(eq("a-refresh-token"), any()))
                .thenReturn(new LoginResponse("new-access", 900_000L, "rotated-refresh", 2_592_000_000L, 13L));

        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"a-refresh-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("new-access"))
                .andExpect(jsonPath("$.refreshToken").value("rotated-refresh"))
                .andExpect(jsonPath("$.expiresIn").value(900_000L));

        // No widget is on screen when a token lapses mid-session, so a captcha here would be a
        // challenge nobody could answer.
        verifyNoInteractions(captchaVerifier);
    }

    @Test
    @DisplayName("a refresh token the server will not honour is 401, and says nothing else")
    void aRejectedRefreshTokenIs401() throws Exception {
        when(authenticationService.refresh(any(), any()))
                .thenThrow(new GlobalException(ExceptionIdentifier.INVALID_CREDENTIALS));

        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"spent-or-unknown\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("an empty refresh token is a 400 before the service is asked")
    void anEmptyRefreshTokenIsRejected() throws Exception {
        mvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authenticationService);
    }

    @Test
    @DisplayName("logout answers 204 with no body and withdraws the token it was given")
    void logoutAnswers204() throws Exception {
        mvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"still-live\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(authenticationService).logout("still-live");
    }

    @Test
    @DisplayName("logging out with a token that is already gone looks identical to logging out")
    void logoutOfASpentTokenIsIndistinguishable() throws Exception {
        MvcResult live = mvc.perform(post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"still-live\"}")).andReturn();

        MvcResult spent = mvc.perform(post("/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"already-spent\"}")).andReturn();

        assertThat(live.getResponse().getStatus()).isEqualTo(spent.getResponse().getStatus());
        assertThat(live.getResponse().getContentAsString())
                .isEqualTo(spent.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("the device list answers 200 with what the caller can end, and never a token")
    void theDeviceListAnswersWithSessions() throws Exception {
        when(authenticationService.listSessions(caller)).thenReturn(List.of(new ActiveDeviceDto(
                12L, "203.0.113.7", "Mozilla/5.0",
                Instant.parse("2026-08-01T09:00:00Z"),
                Instant.parse("2026-09-02T11:30:00Z"),
                Instant.parse("2026-10-02T11:30:00Z"))));

        MvcResult listed = mvc.perform(get("/auth/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(12))
                .andExpect(jsonPath("$[0].ipAddress").value("203.0.113.7"))
                .andExpect(jsonPath("$[0].signedInAt").exists())
                .andReturn();

        assertThat(listed.getResponse().getContentAsString())
                .as("nothing about the token itself belongs in a list a browser renders")
                .doesNotContain("token", "Hash");
    }

    @Test
    @DisplayName("ending a device answers 204 and passes the caller, not just the id")
    void endingADeviceAnswers204() throws Exception {
        mvc.perform(delete("/auth/devices/12"))
                .andExpect(status().isNoContent());

        verify(authenticationService).revokeSession(caller, 12L);
    }

    @Test
    @DisplayName("a session that is not the caller's is 404, and nothing is withdrawn")
    void endingSomebodyElsesDeviceIs404() throws Exception {
        doThrow(new GlobalException(ExceptionIdentifier.SESSION_NOT_FOUND))
                .when(authenticationService).revokeSession(caller, 99L);

        mvc.perform(delete("/auth/devices/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }

    @Test
    @DisplayName("neither device route goes anywhere near the captcha verifier")
    void theDeviceRoutesDoNotChallenge() throws Exception {
        when(authenticationService.listSessions(caller)).thenReturn(List.of());

        mvc.perform(get("/auth/devices")).andExpect(status().isOk());
        mvc.perform(delete("/auth/devices/12")).andExpect(status().isNoContent());

        verifyNoInteractions(captchaVerifier);
        verify(passwordResetService, never()).requestReset(any());
    }
}
