package pl.myproject.kanbanproject2.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The board is edited by every member at once and the client sends one position PATCH per card in
 * a reordered cell, so two people dragging in the same column race by construction. The
 * {@code @Version} columns added alongside this test make the losing write fail its
 * {@code UPDATE ... WHERE version = ?}; Hibernate raises that as an
 * {@link ObjectOptimisticLockingFailureException}, which Spring's persistence layer hands to the
 * advice as a {@code DataAccessException}.
 *
 * <p>Without a handler it reaches the catch-all and is answered 500 - logged at {@code error} and
 * counted against the alert on the error rate, for what is really the caller holding a stale copy.
 * This pins it to 409, the same way {@link ClientErrorStatusTest} pins the request mistakes.
 */
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

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
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
}
