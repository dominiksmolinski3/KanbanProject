package pl.myproject.kanbanproject2.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OptimisticLockConflictTest {
    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @GetMapping
        public String stale() {
            throw new ObjectOptimisticLockingFailureException("Task", 1);
        }
    }

    private MockMvc mvc;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(meterRegistry))
                .build();
    }

    @Test
    @DisplayName("a stale write loses the race with 409, not 500")
    void staleWriteIs409() throws Exception {
        mvc.perform(get("/probe"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    @DisplayName("the conflict body carries a message and no internals")
    void conflictBodyIsClean() throws Exception {
        var body = mvc.perform(get("/probe"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .contains("reload")
                .doesNotContain("ObjectOptimisticLockingFailureException")
                .doesNotContain("org.springframework");
    }

    @Test
    @DisplayName("each stale write counts a conflict, so the rate is visible on /actuator/metrics")
    void staleWriteCountsAConflict() throws Exception {
        mvc.perform(get("/probe"));
        mvc.perform(get("/probe"));

        assertThat(meterRegistry.get(GlobalExceptionHandler.OPTIMISTIC_LOCK_CONFLICTS_COUNTER)
                .counter().count())
                .isEqualTo(2.0);
    }
}
