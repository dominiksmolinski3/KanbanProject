package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogValuesTest {

    @Test
    @DisplayName("a value from the request cannot start a second log line")
    void flattensLineBreaks() {
        assertThat(LogValues.oneLine("/api/x\r\n2026-10-29 INFO forged")).isEqualTo("/api/x__2026-10-29 INFO forged");
    }

    @Test
    @DisplayName("an ordinary value and a missing one pass through")
    void leavesOrdinaryValuesAlone() {
        assertThat(LogValues.oneLine("/api/boards/7")).isEqualTo("/api/boards/7");
        assertThat(LogValues.oneLine(null)).isNull();
    }
}
