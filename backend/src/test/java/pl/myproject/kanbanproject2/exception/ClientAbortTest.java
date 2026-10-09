package pl.myproject.kanbanproject2.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class ClientAbortTest {

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping("/broken-pipe")
        public String brokenPipe() {
            throw new HttpMessageNotWritableException("Could not write JSON: ServletOutputStream failed to write",
                    new IOException("Broken pipe"));
        }

        @GetMapping("/async-unusable")
        public String asyncUnusable() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to write");
        }

        @GetMapping("/unserializable")
        public String unserializable() {
            throw new HttpMessageNotWritableException("Could not write JSON: no serializer for Widget");
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("a client that hangs up mid-response is not an error: nothing logged at ERROR, no body written")
    void brokenPipeIsQuiet(CapturedOutput output) throws Exception {
        var body = mvc.perform(get("/probe/broken-pipe"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEmpty();
        assertThat(output).doesNotContain("ERROR").doesNotContain("Unhandled exception");
    }

    @Test
    @DisplayName("Spring's own disconnected-response exception is treated the same way")
    void asyncRequestNotUsableIsQuiet(CapturedOutput output) throws Exception {
        var body = mvc.perform(get("/probe/async-unusable"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEmpty();
        assertThat(output).doesNotContain("Unhandled exception");
    }

    @Test
    @DisplayName("a response that cannot be serialised is still a 500 logged at ERROR")
    void serializationFailureStillFails(CapturedOutput output) throws Exception {
        mvc.perform(get("/probe/unserializable"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(output).contains("Unhandled exception");
    }
}
