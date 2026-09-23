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

class BoardEventCoverageTest {
    private static final Path SOURCE = Path.of("src", "main", "java", "pl", "myproject", "kanbanproject2");

    private static final Map<String, Pattern> UNANNOUNCED = Map.of(
            "task/TaskService.java", Pattern.compile("taskMapper\\.apply\\(\\s*taskRepository\\.save\\("),
            "layout/column/ColumnService.java", Pattern.compile("columnMapper\\.apply\\(\\s*columnRepository\\.save\\("),
            "layout/row/RowService.java", Pattern.compile("rowMapper\\.apply\\(\\s*rowRepository\\.save\\("),
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
