package pl.myproject.kanbanproject2.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RowLockConflictTest {

    private static final Path APP_PROPERTIES = Path.of("src", "main", "resources", "application.properties");

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping
        public String deadlocked() {
            throw new CannotAcquireLockException("could not execute statement [ERROR: deadlock detected]");
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
    @DisplayName("a write that loses a row-lock race answers 409, the conflict the client already reloads on")
    void lockFailureIs409() throws Exception {
        mvc.perform(get("/probe"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    @DisplayName("the conflict body names no SQL and no internals")
    void lockFailureBodyIsClean() throws Exception {
        var body = mvc.perform(get("/probe"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("deadlock")
                .doesNotContain("org.springframework");
    }

    @Test
    @DisplayName("updates flush in primary-key order, so concurrent reorders lock rows in one order and cannot deadlock")
    void updatesFlushInKeyOrder() throws IOException {
        var properties = new Properties();
        try (Reader reader = Files.newBufferedReader(APP_PROPERTIES)) {
            properties.load(reader);
        }

        assertThat(properties.getProperty("spring.jpa.properties.hibernate.order_updates"))
                .as("reorderColumns/Rows/Tasks update rows in the order the client sent; without key-ordered "
                        + "flushes two shuffled reorders take the same locks in opposite orders and deadlock")
                .isEqualTo("true");
    }
}
