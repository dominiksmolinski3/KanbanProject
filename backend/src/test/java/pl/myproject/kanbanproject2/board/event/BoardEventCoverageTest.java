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
 * The guard over the one mistake this feature makes easy, and it is a silent one.
 *
 * <p>A mutation that forgets to announce still works: the row is written, the response is right,
 * every existing test passes, and the caller's own screen updates because it applied the change
 * optimistically. The only symptom is <em>somebody else's</em> browser sitting on a board that is
 * quietly wrong until they reload - which no unit test here can see, because there is no second
 * browser in a unit test.
 *
 * <p>So the three services funnel every save-and-map through a private {@code saveAndAnnounce},
 * and this reads their source and fails when a save-and-map appears outside it. That turns
 * "somebody forgot a line" into "somebody wrote a different method call", which is a thing a
 * regular expression can see. It is the same shape as {@code DeadLetterAlertTest} and
 * {@code ClientRoutesExistTest}: a rule that lives in two places, checked in the one place that
 * can see both.
 *
 * <p><b>What it cannot see</b>, stated rather than implied: a mutation that neither saves nor maps
 * - a delete, or a change written through a different repository - has to call the publisher by
 * hand, and this only checks that each such file calls it at all, not that every one of its
 * branches does. It is a tripwire on the common shape, not a proof.
 */
class BoardEventCoverageTest {

    /** Tests run with {@code backend/} as the working directory. */
    private static final Path SOURCE = Path.of("src", "main", "java", "pl", "myproject", "kanbanproject2");

    /** Each service, and the save-and-map it must not perform outside {@code saveAndAnnounce}. */
    private static final Map<String, Pattern> UNANNOUNCED = Map.of(
            "task/TaskService.java", Pattern.compile("taskMapper\\.apply\\(\\s*taskRepository\\.save\\("),
            "layout/column/ColumnService.java", Pattern.compile("columnMapper\\.apply\\(\\s*columnRepository\\.save\\("),
            "layout/row/RowService.java", Pattern.compile("rowMapper\\.apply\\(\\s*rowRepository\\.save\\("));

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
                "task/TaskService.java", "layout/column/ColumnService.java", "layout/row/RowService.java");

        for (String file : deleting) {
            String body = read(file);
            int delete = body.indexOf("Repository.delete(");

            assertThat(delete).as("%s no longer deletes anything; this check has gone stale", file).isPositive();
            assertThat(body.substring(delete, Math.min(body.length(), delete + 400)))
                    .as("%s removes a row without telling the board's other viewers", file)
                    .contains("boardEvents.");
        }
    }

    private static String read(String relative) throws IOException {
        Path path = SOURCE.resolve(relative);
        assertThat(path).as("%s has moved or gone; this guard reads it by path", relative).exists();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
