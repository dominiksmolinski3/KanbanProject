package pl.myproject.kanbanproject2.user.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import pl.myproject.kanbanproject2.config.security.AuthenticationService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeviceControllerHttpTest {
    private AuthenticationService authenticationService;
    private MockMvc mvc;
    private User caller;

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
        caller = new User("someone", "someone@example.test", "hashed");
        caller.setId(7);
        mvc = MockMvcBuilders.standaloneSetup(new DeviceController(authenticationService))
                .setCustomArgumentResolvers(new PrincipalResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("the device list answers 200 with what the caller can end, and never a token")
    void theDeviceListAnswersWithSessions() throws Exception {
        when(authenticationService.listSessions(caller)).thenReturn(List.of(new ActiveDeviceDto(
                12L, "203.0.113.7", "Mozilla/5.0",
                Instant.parse("2026-08-01T09:00:00Z"),
                Instant.parse("2026-09-02T11:30:00Z"),
                Instant.parse("2026-10-02T11:30:00Z"),
                false)));

        MvcResult listed = mvc.perform(get("/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(12))
                .andExpect(jsonPath("$[0].ipAddress").value("203.0.113.7"))
                .andExpect(jsonPath("$[0].signedInAt").exists())
                .andExpect(jsonPath("$[0].redacted").value(false))
                .andReturn();

        assertThat(listed.getResponse().getContentAsString())
                .as("nothing about the token itself belongs in a list a browser renders")
                .doesNotContain("token", "Hash");
    }

    @Test
    @DisplayName("ending a device answers 204 and passes the caller, not just the id")
    void endingADeviceAnswers204() throws Exception {
        mvc.perform(delete("/devices/12"))
                .andExpect(status().isNoContent());

        verify(authenticationService).revokeSession(caller, 12L);
    }

    @Test
    @DisplayName("a session that is not the caller's is 404, and nothing is withdrawn")
    void endingSomebodyElsesDeviceIs404() throws Exception {
        doThrow(new GlobalException(ExceptionIdentifier.SESSION_NOT_FOUND))
                .when(authenticationService).revokeSession(caller, 99L);

        mvc.perform(delete("/devices/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));
    }
}
