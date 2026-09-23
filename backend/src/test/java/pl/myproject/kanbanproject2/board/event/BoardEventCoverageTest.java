package pl.myproject.kanbanproject2.board.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard over the one mistake this feature makes easy, and it is a silent one: a mutation that
 * forgets to announce still works, every existing test passes, and the only symptom is
 * <em>somebody else's</em> browser sitting on a board that is quietly wrong — which no unit test
 * can see, since there is no second browser in one.
 *
 * <p>So the four services funnel every save-and-map through a private {@code saveAndAnnounce}, and
 * this reads their source and fails when a save-and-map appears outside it — the same
 * rule-in-two-places shape as {@code MetricAlertsMatchTheMetersTest} and {@code ClientRoutesExistTest}.
 *
 * <p><b>What it cannot see:</b> a mutation that neither saves nor maps (a delete, or a write through
 * a different repository) has to call the publisher by hand, and this only checks that such a file
 * calls it at all, not that every branch does. A tripwire on the common shape, not a proof.
 */
class BoardEventCoverageTest {

    /** Tests run with {@code backend/} as the working directory. */
    private static final Path SOURCE = Path.of("src", "main", "java", "pl", "myproject", "kanbanproject2");

    /** Each service, and the save-and-map it must not perform outside {@code saveAndAnnounce}. */
    private static final Map<String, Pattern> UNANNOUNCED = Map.of(
            "task/TaskService.java", Pattern.compile("taskMapper\\.apply\\(\\s*taskRepository\\.save\\("),
            "layout/column/ColumnService.java", Pattern.compile("columnMapper\\.apply\\(\\s*columnRepository\\.save\\("),
            "layout/row/RowService.java", Pattern.compile("rowMapper\\.apply\\(\\s*rowRepository\\.save\\("),
            // SYNC-01: a card carries its open-subtask count, so a subtask write is a card write.
            "task/subtask/SubTaskService.java",
            Pattern.compile("subTaskMapper\\.toDto\\(\\s*subTaskRepository\\.save\\("));

    @Test
    @DisplayName("no service maps a saved entity without announcing the board it belongs to")
    void everySaveAndMapGoesThroughTheHelper() throws IOException {
        for (var entry : UNANNOUNCED.entrySet()) {
            String body = read(entry.getKey());

            assertThat(entry.getValue().matcher(body).results().count())
                    .as("%s saves an entity and maps it without announcing the board. Other viewers "
                            + "will not be told, and nothing else in this suite can notice. Return "
                            + "through saveAndAnnounce instead.", entry.getKey())
                    .isZero();
        }
    }

    @Test
    @DisplayName("each service still has the helper, and the helper still announces")
    void theHelperStillDoesWhatItIsFor() throws IOException {
        for (String file : UNANNOUNCED.keySet()) {
            String body = read(file);

            assertThat(body)
                    .as("%s no longer funnels its saves through one place, so the check above "
                            + "passes by having nothing left to find", file)
                    .contains("private ")
                    .contains("saveAndAnnounce(");
            assertThat(body)
                    .as("%s does not publish a board event at all", file)
                    .contains("boardEvents.");
        }
    }

    @Test
    @DisplayName("the delete paths announce too, which no save-and-map check can reach")
    void deletesAnnounce() throws IOException {
        // A delete maps nothing, so it is invisible to the pattern above and is named here instead.
        List<String> deleting = List.of(
                "task/TaskService.java", "layout/column/ColumnService.java", "layout/row/RowService.java",
                "task/subtask/SubTaskService.java");

        for (String file : deleting) {
            String body = read(file);
            int delete = body.indexOf("Repository.delete(");

            assertThat(delete).as("%s no longer deletes anything; this check has gone stale", file).isPositive();
            assertThat(body.substring(delete, Math.min(body.length(), delete + 400)))
                    .as("%s removes a row without telling the board's other viewers", file)
                    .contains("boardEvents.");
        }
    }

    @Test
    @DisplayName("attaching and removing a file announce it, for the task panel another viewer has open")
    void attachmentWritesAnnounce() throws IOException {
        // Not the save-and-map shape above: an upload writes a blob before it saves, and the panel
        // is the only screen that shows attachments, so the check is on the two writes by name.
        String body = read("task/attachment/TaskAttachmentService.java");

        for (String write : List.of("attachments.save(", "attachments.delete(attachment)")) {
            int at = body.indexOf(write);
            assertThat(at).as("TaskAttachmentService no longer calls %s; this check has gone stale", write)
                    .isPositive();
            assertThat(body.substring(at, Math.min(body.length(), at + 400)))
                    .as("TaskAttachmentService's %s does not tell the board's other viewers", write)
                    .contains("boardEvents.attachmentsChanged(");
        }
    }

    private static String read(String relative) throws IOException {
        Path path = SOURCE.resolve(relative);
        assertThat(path).as("%s has moved or gone; this guard reads it by path", relative).exists();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
