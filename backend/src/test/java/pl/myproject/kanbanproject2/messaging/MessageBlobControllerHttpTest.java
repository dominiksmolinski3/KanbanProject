package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.board.FixedPrincipalResolver;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;

import java.io.ByteArrayInputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MessageBlobControllerHttpTest {

    private MessageBlobService blobs;
    private MockMvc mvc;
    private User caller;

    @BeforeEach
    void setUp() {
        blobs = mock(MessageBlobService.class);
        caller = new User();
        caller.setId(1);
        mvc = MockMvcBuilders.standaloneSetup(new MessageBlobController(blobs))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .build();
    }

    @Test
    @DisplayName("an upload answers 201 with the id and size of the stored bytes")
    void upload() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "attachment", "application/octet-stream", new byte[]{1, 2});
        when(blobs.upload(any(), any(), any())).thenReturn(new MessageBlobDto(9L, 2));

        mvc.perform(multipart("/conversations/5/blobs").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.size").value(2));

        verify(blobs).upload(caller, 5L, file);
    }

    @Test
    @DisplayName("a download is opaque bytes of a known length")
    void download() throws Exception {
        when(blobs.content(caller, 5L, 9L)).thenReturn(new MessageBlobContent(new ByteArrayInputStream(new byte[]{7, 8, 9}), 3));

        mvc.perform(get("/conversations/5/blobs/9"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().longValue("Content-Length", 3))
                .andExpect(content().bytes(new byte[]{7, 8, 9}));
    }

    @Test
    @DisplayName("a blob that is not there answers 404")
    void missing() throws Exception {
        when(blobs.content(caller, 5L, 9L))
                .thenThrow(new GlobalException(ExceptionIdentifier.ATTACHMENT_NOT_FOUND, "Nie znaleziono załącznika"));

        mvc.perform(get("/conversations/5/blobs/9")).andExpect(status().isNotFound());
    }
}
