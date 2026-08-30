package pl.myproject.kanbanproject2.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import pl.myproject.kanbanproject2.config.security.PasswordResetService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.service.AvatarService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code UserControllerOwnershipTest} calls the controller methods directly, so it proves the
 * refusal but not the status the client sees. These go through the dispatcher: the ownership
 * refusal has to arrive as a 403 carrying {@code NOT_ACCOUNT_OWNER}, and the avatar response has
 * to keep the headers that stop user-supplied bytes rendering as a document on the app's origin.
 */
class UserControllerHttpTest {

    private static final Integer CALLER_ID = 1;
    private static final Integer OTHER_ID = 2;

    private UserService userService;
    private AvatarService avatarService;
    private UserMapper userMapper;
    private PasswordResetService passwordResetService;
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
        userService = mock(UserService.class);
        avatarService = mock(AvatarService.class);
        userMapper = mock(UserMapper.class);
        passwordResetService = mock(PasswordResetService.class);

        caller = new User();
        caller.setId(CALLER_ID);

        // The avatar route answers byte[], so the byte-array converter has to be listed
        // alongside Jackson - overriding the converters drops the defaults entirely.
        mvc = MockMvcBuilders.standaloneSetup(new UserController(userService, userMapper, avatarService, passwordResetService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PrincipalResolver())
                .setMessageConverters(
                        new ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();
    }

    @Test
    @DisplayName("the listing answers 200 and never exposes the password field")
    void listAnswers200() throws Exception {
        when(userService.getVisibleUsers(caller))
                .thenReturn(List.of(new UserDto(1, "a@example.test", "a", 3)));

        mvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("a@example.test"))
                .andExpect(jsonPath("$[0].wipLimit").value(3))
                .andExpect(jsonPath("$[0].password").doesNotExist());
    }

    @Test
    @DisplayName("patching another account is a 403 NOT_ACCOUNT_OWNER, and the service is not called")
    void patchingAnotherAccountIs403() throws Exception {
        mvc.perform(patch("/users/" + OTHER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"attacker@evil.test\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ACCOUNT_OWNER"));

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("deleting another account is a 403, and deleting one's own is a 204")
    void deleteChecksOwnership() throws Exception {
        mvc.perform(delete("/users/" + OTHER_ID))
                .andExpect(status().isForbidden());
        verify(userService, never()).deleteUser(any());

        mvc.perform(delete("/users/" + CALLER_ID))
                .andExpect(status().isNoContent());
        verify(userService).deleteUser(CALLER_ID);
    }

    @Test
    @DisplayName("deleting another account's avatar is a 403 as well")
    void avatarDeleteChecksOwnership() throws Exception {
        mvc.perform(delete("/users/" + OTHER_ID + "/avatar"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(avatarService);
    }

    @Test
    @DisplayName("an unauthenticated caller is refused rather than treated as the owner")
    void nullPrincipalIsRefused() throws Exception {
        caller = null;

        mvc.perform(delete("/users/" + CALLER_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ACCOUNT_OWNER"));
    }

    @Test
    @DisplayName("the avatar is served as an attachment with nosniff, never inline")
    void avatarKeepsItsHeaders() throws Exception {
        when(avatarService.getAvatar(CALLER_ID)).thenReturn(new byte[]{1, 2, 3});
        when(avatarService.getAvatarContentType(CALLER_ID)).thenReturn("image/png");

        mvc.perform(get("/users/" + CALLER_ID + "/avatar"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"));
    }

    @Test
    @DisplayName("a missing avatar reaches the client as a 404, not an empty 200")
    void missingAvatarIs404() throws Exception {
        when(avatarService.getAvatar(OTHER_ID))
                .thenThrow(new GlobalException(ExceptionIdentifier.AVATAR_NOT_FOUND));

        mvc.perform(get("/users/" + OTHER_ID + "/avatar"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AVATAR_NOT_FOUND"));
    }

    @Test
    @DisplayName("the WIP status answers a record, so a caller reads fields rather than a bare boolean")
    void wipStatusAnswersARecord() throws Exception {
        when(userService.getWipStatus(caller, CALLER_ID)).thenReturn(new WipStatusDto(CALLER_ID, 5, 5, false));

        mvc.perform(get("/users/" + CALLER_ID + "/wip-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(CALLER_ID))
                .andExpect(jsonPath("$.wipLimit").value(5))
                .andExpect(jsonPath("$.assignedCount").value(5))
                .andExpect(jsonPath("$.withinLimit").value(false));
    }

    @Test
    @DisplayName("the WIP limit route reaches the service with the parsed body")
    void wipLimitRoute() throws Exception {
        when(userService.updateWipLimit(eq(caller), eq(CALLER_ID), eq(5)))
                .thenReturn(new UserDto(CALLER_ID, "a@example.test", "a", 5));

        mvc.perform(patch("/users/" + CALLER_ID + "/wip-limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wipLimit").value(5));
    }

    @Test
    @DisplayName("a missing user reaches the client as a 404")
    void missingUserIs404() throws Exception {
        when(userService.getUserById(caller, 404))
                .thenThrow(new GlobalException(ExceptionIdentifier.USER_NOT_FOUND));

        mvc.perform(get("/users/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }
}
