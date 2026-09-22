package pl.myproject.kanbanproject2.board;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FEAT-08's other half of {@code BoardEventCoverageTest}'s shape: a mutation that forgets to
 * announce is silent, and a mutation that forgets the <em>write</em> check is worse - a viewer gets
 * full access rather than a stale screen, and every existing unit test still passes because they all
 * hand a caller who already owns the board. So this reads the same three services'
 * source and fails the build when a known mutation entry point's body has no call to
 * {@code BoardService.requireWritable}, plus {@code ChatController}'s board-send path, which asks
 * {@code isWritable} directly since a refusal there is answered rather than thrown.
 *
 * <p><b>What it cannot see:</b> like its sibling, this is a tripwire on the methods named below, not
 * a proof that every future mutation remembers the check - a wholly new method has to be added to
 * the map here as well as to the service, which is the same trade {@code BoardEventCoverageTest}
 * makes and states for the same reason.
 */
class WriteAccessCoverageTest {

    private static final Path SOURCE = Path.of("src", "main", "java", "pl", "myproject", "kanbanproject2");

    /** Every mutation entry point, keyed by the file it lives in and its unique signature prefix. */
    private static final Map<String, List<String>> MUTATIONS = Map.of(
            "task/TaskService.java", List.of(
                    "public TaskDto addTask(",
                    "public void deleteTask(",
                    "public TaskDto patchTask(",
                    "public TaskDto assignUserToTask(",
                    "public TaskDto removeUserFromTask(",
                    "public TaskDto updateTaskPosition(",
                    "public List<TaskDto> reorderTasks(",
                    "public TaskDto addLabelToTask(",
                    "public TaskDto removeLabelFromTask(",
                    "public TaskDto updateTaskLabels(",
                    "public TaskDto assignParentTask(",
                    "public TaskDto removeParentTask(",
                    "public TaskDto updateTaskCompletion(",
                    "public TaskDto setDailyFocus("),
            "layout/column/ColumnService.java", List.of(
                    "public ColumnResponseDto addNewColumn(",
                    "public ColumnDto patchColumn(",
                    "public void deleteColumn(",
                    "public ColumnDto updateColumnPosition(",
                    "public List<ColumnDto> reorderColumns("),
            "layout/row/RowService.java", List.of(
                    "public RowResponseDto createRow(",
                    "public RowDto patchRow(",
                    "public void deleteRow(",
                    "public RowDto updateRowPosition(",
                    "public List<RowDto> reorderRows("),
            // FEAT-06: a viewer reads a card's thread and writes none of it.
            "task/comment/TaskCommentService.java", List.of(
                    "public TaskCommentDto add(",
                    "public TaskCommentDto edit(",
                    "public void delete("));

    @Test
    @DisplayName("every task/column/row mutation asks BoardService.requireWritable, not just findX's visibility check")
    void everyMutationChecksWritability() throws IOException {
        for (var entry : MUTATIONS.entrySet()) {
            String body = read(entry.getKey());
            for (String signature : entry.getValue()) {
                String method = methodBody(body, signature, entry.getKey());
                assertThat(method)
                        .as("%s#%s does not call BoardService.requireWritable - a viewer could reach it "
                                + "with full write access", entry.getKey(), signature)
                        .contains("requireWritable(");
            }
        }
    }

    @Test
    @DisplayName("chat's board-send path asks BoardService.isWritable before it accepts a message")
    void chatSendChecksWritability() throws IOException {
        String body = read("controller/ChatController.java");
        String method = methodBody(body, "public void sendMessage(", "controller/ChatController.java");

        assertThat(method)
                .as("ChatController#sendMessage no longer checks isWritable - a viewer could post to "
                        + "a board's conversation, which FEAT-08 says read-only means read-only for")
                .contains("isWritable(");
        assertThat(method)
                .as("the refusal has stopped naming ChatRefusal.READ_ONLY_BOARD")
                .contains("READ_ONLY_BOARD");
    }

    /**
     * The brace-balanced body of the method whose signature starts with {@code signature} - found by
     * locating the signature, then the first {@code {} that follows it (nothing before a method body
     * opens can contain one: annotations and parameter lists use parentheses only), then counting
     * braces until they close.
     */
    private static String methodBody(String source, String signature, String file) {
        int start = source.indexOf(signature);
        assertThat(start).as("%s has no method starting with '%s'", file, signature).isNotNegative();

        int open = source.indexOf('{', start);
        assertThat(open).as("%s: no body found after '%s'", file, signature).isNotNegative();

        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError(file + ": unbalanced braces reading '" + signature + "'");
    }

    private static String read(String relative) throws IOException {
        Path path = SOURCE.resolve(relative);
        assertThat(path).as("%s has moved or gone; this guard reads it by path", relative).exists();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
