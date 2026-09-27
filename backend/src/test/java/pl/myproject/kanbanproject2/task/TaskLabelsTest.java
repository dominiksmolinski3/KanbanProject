package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskLabelsTest {

    @Test
    @DisplayName("a label as long as the column holds, on a task at the limit, is accepted")
    void theLimitsThemselvesAreAccepted() {
        var labels = new ArrayList<>(IntStream.range(1, TaskLabels.MAX_COUNT).mapToObj(i -> "l" + i).toList());
        labels.add("x".repeat(TaskLabels.MAX_LENGTH));

        assertThatCode(() -> TaskLabels.requireValid(labels)).doesNotThrowAnyException();
        assertThatCode(() -> TaskLabels.requireValid(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a label longer than task_labels.label is refused before it reaches the database")
    void anOverlongLabelIsRefused() {
        expectInvalid(List.of("x".repeat(TaskLabels.MAX_LENGTH + 1)));
    }

    @Test
    @DisplayName("more labels than a task may carry are refused")
    void tooManyLabelsAreRefused() {
        expectInvalid(IntStream.rangeClosed(0, TaskLabels.MAX_COUNT).mapToObj(i -> "l" + i).toList());
    }

    @Test
    @DisplayName("a blank or null label is refused")
    void blankLabelsAreRefused() {
        expectInvalid(List.of("  "));
        expectInvalid(Arrays.asList("ok", null));
    }

    private static void expectInvalid(List<String> labels) {
        assertThatThrownBy(() -> TaskLabels.requireValid(labels))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.INVALID_LABELS);
    }
}
