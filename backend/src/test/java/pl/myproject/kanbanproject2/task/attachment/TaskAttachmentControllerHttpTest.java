package pl.myproject.kanbanproject2.task.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The download response, which is the whole reason the storage account can be shut off.
 *
 * <p>{@code TaskAttachmentServiceTest} proves who may reach an attachment; this proves what the
 * response looks like once they may, and that is where the risk moved when the bytes came back onto
 * the request path. Serving them from Azure's origin made a rendered upload somebody else's
 * problem. Serving them from <em>this</em> origin means an HTML or SVG file that renders instead of
 * downloading is same-origin with the board and every token in it — so
 * {@code Content-Disposition: attachment} is a security control here, not a convenience, and it is
 * asserted rather than assumed.
 */
class TaskAttachmentControllerHttpTest {

    private static final byte[] CONTENT = "a small attachment".getBytes(StandardCharsets.UTF_8);

    private TaskAttachmentService attachmentService;
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
        attachmentService = mock(TaskAttachmentService.class);
        caller = new User();
        caller.setId(1);

        // The content route answers a Resource, so that converter has to be listed alongside
        // Jackson - overriding the converters drops the defaults entirely.
        mvc = MockMvcBuilders.standaloneSetup(new TaskAttachmentController(attachmentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PrincipalResolver())
                .setMessageConverters(
                        new ResourceHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(
                                new ObjectMapper().registerModule(new JavaTimeModule())))
                .build();
    }

    private static TaskAttachmentDto dto() {
        return new TaskAttachmentDto(5L, 42, "design.pdf", "application/pdf", CONTENT.length,
                1, "John Doe", Instant.parse("2026-04-01T10:15:30Z"));
    }

    private static TaskAttachmentContent content(String fileName, String contentType) {
        return new TaskAttachmentContent(new ByteArrayInputStream(CONTENT), fileName, contentType,
                CONTENT.length);
    }

    @Test
    @DisplayName("the listing answers 200 with the rows, and never a blob name")
    void listAnswers200() throws Exception {
        when(attachmentService.list(caller, 42)).thenReturn(List.of(dto()));

        mvc.perform(get("/tasks/42/attachments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("design.pdf"))
                .andExpect(jsonPath("$[0].uploadedByName").value("John Doe"))
                .andExpect(jsonPath("$[0].blobName").doesNotExist());
    }

    @Test
    @DisplayName("an upload answers 201 with the row that was written")
    void uploadAnswers201() throws Exception {
        when(attachmentService.upload(eq(caller), eq(42), any())).thenReturn(dto());

        mvc.perform(multipart("/tasks/42/attachments")
                        .file(new MockMultipartFile("file", "design.pdf", "application/pdf", CONTENT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    @DisplayName("a download forces a save under the stored name, as the stored type")
    void contentIsAnAttachment() throws Exception {
        when(attachmentService.content(caller, 42, 5L)).thenReturn(content("design.pdf", "application/pdf"));

        var response = mvc.perform(get("/tasks/42/attachments/5/content"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT.length))
                .andReturn()
                .getResponse();

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .as("served from the app's own origin, so a rendered upload would be same-origin "
                        + "with the board - this header is what stops that")
                .startsWith("attachment;")
                .contains("design.pdf");
        assertThat(response.getContentAsByteArray()).isEqualTo(CONTENT);
    }

    @Test
    @DisplayName("a name that is not ASCII survives into the header")
    void contentEncodesANonAsciiName() throws Exception {
        when(attachmentService.content(caller, 42, 5L))
                .thenReturn(content("sprawozdanie kwartalne.pdf", "application/pdf"));

        var disposition = mvc.perform(get("/tasks/42/attachments/5/content"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.CONTENT_DISPOSITION);

        assertThat(disposition).contains("filename*=UTF-8''");
    }

    @Test
    @DisplayName("a row with no usable type is served as octet-stream rather than failing")
    void contentFallsBackToOctetStream() throws Exception {
        when(attachmentService.content(caller, 42, 5L)).thenReturn(content("notes", ""));

        mvc.perform(get("/tasks/42/attachments/5/content"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/octet-stream"));
    }

    @Test
    @DisplayName("an attachment the caller may not see is a 404, with nothing in the body about it")
    void contentAnswers404() throws Exception {
        when(attachmentService.content(caller, 42, 5L))
                .thenThrow(new GlobalException(ExceptionIdentifier.ATTACHMENT_NOT_FOUND));

        mvc.perform(get("/tasks/42/attachments/5/content"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("an upload with no storage configured answers 503 rather than a 500")
    void uploadAnswers503WhenStorageIsOff() throws Exception {
        when(attachmentService.upload(eq(caller), eq(42), any()))
                .thenThrow(new GlobalException(ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE));

        mvc.perform(multipart("/tasks/42/attachments")
                        .file(new MockMultipartFile("file", "design.pdf", "application/pdf", CONTENT)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ATTACHMENT_STORAGE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("a delete answers 204 and passes the caller through")
    void deleteAnswers204() throws Exception {
        mvc.perform(delete("/tasks/42/attachments/5"))
                .andExpect(status().isNoContent());

        verify(attachmentService).delete(caller, 42, 5L);
    }
}
