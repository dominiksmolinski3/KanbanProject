package pl.myproject.kanbanproject2.user.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import pl.myproject.kanbanproject2.config.security.EmailChangeService;
import pl.myproject.kanbanproject2.config.security.LoginResponse;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmailChangeControllerTest {
    private EmailChangeService service;
    private MockMvc mvc;
    private User caller;

    @BeforeEach
    void setUp() {
        service = mock(EmailChangeService.class);
        caller = new User();
        caller.setId(1);
        mvc = MockMvcBuilders.standaloneSetup(new EmailChangeController(service, mock(ClientIpResolver.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return User.class.isAssignableFrom(parameter.getParameterType());
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                                  NativeWebRequest request, WebDataBinderFactory binders) {
                        return caller;
                    }
                })
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();
    }

    @Test
    @DisplayName("a request for one's own account is 202 with no body")
    void ownRequestIsAccepted() throws Exception {
        mvc.perform(post("/users/1/email-change").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"new@example.test\",\"currentPassword\":\"pw\"}"))
                .andExpect(status().isAccepted());

        verify(service).requestChange(eq(caller), eq(new EmailChangeRequest("new@example.test", "pw")));
    }

    @Test
    @DisplayName("another account's address cannot be changed: 403, and the service is not called")
    void anotherAccountIsRefused() throws Exception {
        mvc.perform(post("/users/2/email-change").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newEmail\":\"new@example.test\",\"currentPassword\":\"pw\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ACCOUNT_OWNER"));
        mvc.perform(post("/users/2/email-change/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("confirming answers the new session, and a malformed code never reaches the service")
    void confirmAnswersASession() throws Exception {
        when(service.confirmChange(eq(caller), any(), any()))
                .thenReturn(new LoginResponse("jwt", 900_000, "refresh", 1L, 7L));

        mvc.perform(post("/users/1/email-change/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt"))
                .andExpect(jsonPath("$.sessionId").value(7));

        mvc.perform(post("/users/1/email-change/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"12ab\"}"))
                .andExpect(status().isBadRequest());
    }
}
