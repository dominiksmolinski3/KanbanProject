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

class WriteAccessCoverageTest {
    private static final Path SOURCE = Path.of("src", "main", "java", "pl", "myproject", "kanbanproject2");

    private static final Map<String, List<String>> MUTATIONS = Map.of(
            "task/TaskService.java", List.of(
                    "public TaskDto addTask(",
                    "public void deleteTask(",
                    "public TaskDto patchTask(",
                    "public TaskDto assignUserToTask(",
                    "public TaskDto removeUserFromTask(",
                    "public TaskDto updateTaskPosition(",
                    "public List<TaskDto> reorderTasks(",
                    "public TaskDto updateTaskLabels(",
                    "public TaskDto assignParentTask(",
                    "public TaskDto removeParentTask(",
                    "public TaskDto updateTaskCompletion(",
                    "public TaskDto setDailyFocus("),
            "layout/column/ColumnService.java", List.of(
                    "public ColumnDto addNewColumn(",
                    "public ColumnDto patchColumn(",
                    "public void deleteColumn(",
                    "public ColumnDto updateColumnPosition(",
                    "public List<ColumnDto> reorderColumns("),
            "layout/row/RowService.java", List.of(
                    "public RowDto createRow(",
                    "public RowDto patchRow(",
                    "public void deleteRow(",
                    "public RowDto updateRowPosition(",
                    "public List<RowDto> reorderRows("),
            "task/subtask/SubTaskService.java", List.of(
                    "public SubTaskDto addSubTask(",
                    "public void deleteSubTask(",
                    "public SubTaskDto patchSubTask(",
                    "public SubTaskDto assignTaskToSubTask(",
                    "public SubTaskDto toggleSubTaskCompletion(",
                    "public SubTaskDto updateSubTaskPosition("),
            "task/attachment/TaskAttachmentService.java", List.of(
                    "public TaskAttachmentDto upload(",
                    "public void delete("),
            "task/comment/TaskCommentService.java", List.of(
                    "public TaskCommentDto add(",
                    "public TaskCommentDto edit(",
                    "public void delete("));

    @Test
    @DisplayName("every task/column/row/subtask/attachment mutation asks BoardService.requireWritable, not just findX's visibility check")
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
    @DisplayName("posting to a board channel asks BoardService.requireWritable before it accepts a message")
    void boardChannelPostChecksWritability() throws IOException {
        String body = read("messaging/MessageDeliveryService.java");
        String method = methodBody(body, "public PostedDto post(", "messaging/MessageDeliveryService.java");

        assertThat(method)
                .as("MessageDeliveryService#post no longer checks requireWritable - a viewer could post to "
                        + "a board's channel it may only read")
                .contains("requireWritable(");
    }

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
