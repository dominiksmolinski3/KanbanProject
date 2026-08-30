package pl.myproject.kanbanproject2.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.file.File;
import pl.myproject.kanbanproject2.board.FixedPrincipalResolver;
import pl.myproject.kanbanproject2.file.FileService;
import pl.myproject.kanbanproject2.user.User;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nothing in the frontend reaches these routes yet, which is why they had no test either. They are
 * still live on the API, and SEC-05 is about who may call them - so the response shape is worth
 * pinning before an owner column changes it: the download is an attachment, never inline, and an
 * unparseable stored content type falls back to octet-stream rather than throwing a 500.
 */
class FileControllerHttpTest {

    private FileService fileService;
    private MockMvc mvc;
    private User caller;

    @BeforeEach
    void setUp() {
        fileService = mock(FileService.class);
        caller = new User();
        caller.setId(1);
        mvc = MockMvcBuilders.standaloneSetup(new FileController(fileService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .build();
    }

    private static File file(Long id, String name, String type, byte[] data) {
        var entity = new File(name, type, data);
        entity.setId(id);
        return entity;
    }

    @Test
    @DisplayName("uploading answers 201 with a Location header and the stored metadata")
    void uploadAnswers201() throws Exception {
        when(fileService.saveFile(any(), any()))
                .thenReturn(file(9L, "notes.txt", "text/plain", new byte[]{1, 2, 3}));

        var upload = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[]{1, 2, 3});

        mvc.perform(multipart("/files/upload").file(upload))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.endsWith("/files/9")))
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.name").value("notes.txt"))
                .andExpect(jsonPath("$.contentType").value("text/plain"))
                .andExpect(jsonPath("$.size").value(3));
    }

    @Test
    @DisplayName("a rejected upload reaches the client as a 400, not a 500")
    void rejectedUploadIs400() throws Exception {
        when(fileService.saveFile(any(), any()))
                .thenThrow(new IllegalArgumentException("The uploaded file must not be empty"));

        var empty = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[0]);

        mvc.perform(multipart("/files/upload").file(empty))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("downloading serves the stored bytes as an attachment under the stored type")
    void downloadIsAnAttachment() throws Exception {
        when(fileService.getFile(caller, 9L))
                .thenReturn(file(9L, "notes.txt", "text/plain", new byte[]{1, 2, 3}));

        mvc.perform(get("/files/9"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("notes.txt")))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("a blank stored content type falls back to octet-stream")
    void blankTypeFallsBackToOctetStream() throws Exception {
        when(fileService.getFile(caller, 9L)).thenReturn(file(9L, "blob", "  ", new byte[]{7}));

        mvc.perform(get("/files/9"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_OCTET_STREAM_VALUE));
    }

    @Test
    @DisplayName("a missing file is a 404 carrying FILE_NOT_FOUND")
    void missingFileIs404() throws Exception {
        when(fileService.getFile(caller, 404L))
                .thenThrow(new GlobalException(ExceptionIdentifier.FILE_NOT_FOUND));

        mvc.perform(get("/files/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    }

    @Test
    @DisplayName("deleting answers 204, and deleting a missing file answers 404")
    void deleteStatuses() throws Exception {
        mvc.perform(delete("/files/9"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(fileService).deleteFile(caller, 9L);

        doThrow(new GlobalException(ExceptionIdentifier.FILE_NOT_FOUND))
                .when(fileService).deleteFile(caller, 404L);

        mvc.perform(delete("/files/404"))
                .andExpect(status().isNotFound());
    }
}
