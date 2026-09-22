package pl.myproject.kanbanproject2.user.avatar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.board.FixedPrincipalResolver;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserService;

import java.io.ByteArrayInputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The response this route serves once a caller may reach it - {@code AvatarServiceTest} and
 * {@code UserAvatarControllerOwnershipTest} cover who may. The one thing worth pinning here that
 * {@code TaskAttachmentControllerHttpTest} does not need to: {@code Content-Disposition: inline},
 * never {@code attachment} - the opposite of a task attachment, and safe only because
 * {@code AvatarService.upload} already refused anything outside a validated raster allow-list.
 */
class UserAvatarControllerHttpTest {

    private static final Integer CALLER_ID = 1;
    private static final Integer OTHER_ID = 2;
    private static final byte[] CONTENT = {(byte) 0x89, 'P', 'N', 'G'};

    private AvatarService avatarService;
    private UserService userService;
    private MockMvc mvc;
    private User caller;

    @BeforeEach
    void setUp() {
        avatarService = mock(AvatarService.class);
        userService = mock(UserService.class);
        caller = new User();
        caller.setId(CALLER_ID);

        mvc = MockMvcBuilders.standaloneSetup(new UserAvatarController(avatarService, userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(
                        new ResourceHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("an upload to one's own account answers 204")
    void uploadAnswers204() throws Exception {
        var file = new MockMultipartFile("file", "me.png", "image/png", CONTENT);

        mvc.perform(multipart("/users/" + CALLER_ID + "/avatar").file(file))
                .andExpect(status().isNoContent());

        verify(avatarService).upload(caller, file);
    }

    @Test
    @DisplayName("an upload to another account is a 403, and the service is never reached")
    void uploadToAnotherAccountIs403() throws Exception {
        var file = new MockMultipartFile("file", "me.png", "image/png", CONTENT);

        mvc.perform(multipart("/users/" + OTHER_ID + "/avatar").file(file))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_ACCOUNT_OWNER"));

        verifyNoInteractions(avatarService);
    }

    @Test
    @DisplayName("an upload with no storage configured answers 503 rather than a 500")
    void uploadAnswers503WhenStorageIsOff() throws Exception {
        var file = new MockMultipartFile("file", "me.png", "image/png", CONTENT);
        org.mockito.Mockito.doThrow(new GlobalException(ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE))
                .when(avatarService).upload(any(), any());

        mvc.perform(multipart("/users/" + CALLER_ID + "/avatar").file(file))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_STORAGE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("a rejected content type reaches the client as a 415")
    void uploadAnswersUnsupportedMediaType() throws Exception {
        var file = new MockMultipartFile("file", "me.svg", "image/svg+xml", CONTENT);
        org.mockito.Mockito.doThrow(new GlobalException(ExceptionIdentifier.INVALID_AVATAR_FILE_TYPE))
                .when(avatarService).upload(any(), any());

        mvc.perform(multipart("/users/" + CALLER_ID + "/avatar").file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("INVALID_AVATAR_FILE_TYPE"));
    }

    @Test
    @DisplayName("the avatar is served inline, with the validated type and nosniff")
    void getIsInline() throws Exception {
        when(avatarService.content(CALLER_ID))
                .thenReturn(new AvatarContent(new ByteArrayInputStream(CONTENT), "image/png", CONTENT.length));

        mvc.perform(get("/users/" + CALLER_ID + "/avatar"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT.length))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        verify(userService).requireVisibleUser(caller, CALLER_ID);
    }

    @Test
    @DisplayName("a peer on the same board may read the avatar too, not only the owner")
    void getIsReadableByAPeer() throws Exception {
        when(avatarService.content(OTHER_ID))
                .thenReturn(new AvatarContent(new ByteArrayInputStream(CONTENT), "image/png", CONTENT.length));

        mvc.perform(get("/users/" + OTHER_ID + "/avatar"))
                .andExpect(status().isOk());

        verify(userService).requireVisibleUser(caller, OTHER_ID);
    }

    @Test
    @DisplayName("a caller with no shared board is refused before the store is ever asked")
    void getRefusesAStranger() throws Exception {
        org.mockito.Mockito.doThrow(new GlobalException(ExceptionIdentifier.USER_NOT_FOUND))
                .when(userService).requireVisibleUser(caller, OTHER_ID);

        mvc.perform(get("/users/" + OTHER_ID + "/avatar"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(avatarService);
    }

    @Test
    @DisplayName("a missing avatar reaches the client as a 404, not an empty 200")
    void missingAvatarIs404() throws Exception {
        when(avatarService.content(CALLER_ID))
                .thenThrow(new GlobalException(ExceptionIdentifier.AVATAR_NOT_FOUND));

        mvc.perform(get("/users/" + CALLER_ID + "/avatar"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AVATAR_NOT_FOUND"));
    }

    @Test
    @DisplayName("a row with no usable type is served as octet-stream rather than failing")
    void fallsBackToOctetStream() throws Exception {
        when(avatarService.content(CALLER_ID))
                .thenReturn(new AvatarContent(new ByteArrayInputStream(CONTENT), "", CONTENT.length));

        mvc.perform(get("/users/" + CALLER_ID + "/avatar"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/octet-stream"));
    }

    @Test
    @DisplayName("deleting one's own avatar answers 204")
    void deleteAnswers204() throws Exception {
        mvc.perform(delete("/users/" + CALLER_ID + "/avatar"))
                .andExpect(status().isNoContent());

        verify(avatarService).delete(caller);
    }

    @Test
    @DisplayName("deleting another account's avatar is a 403, and the service is never reached")
    void deleteAnotherAccountIs403() throws Exception {
        mvc.perform(delete("/users/" + OTHER_ID + "/avatar"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(avatarService);
    }
}
