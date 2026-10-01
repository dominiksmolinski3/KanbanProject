package pl.myproject.kanbanproject2.task;

import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.board.CreateBoardRequest;
import pl.myproject.kanbanproject2.board.PatchBoardRequest;
import pl.myproject.kanbanproject2.layout.column.CreateColumnRequest;
import pl.myproject.kanbanproject2.layout.column.PatchColumnRequest;
import pl.myproject.kanbanproject2.layout.row.CreateRowRequest;
import pl.myproject.kanbanproject2.layout.row.PatchRowRequest;
import pl.myproject.kanbanproject2.task.subtask.CreateSubTaskRequest;
import pl.myproject.kanbanproject2.task.subtask.PatchSubTaskRequest;

import java.io.IOException;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TextLimitsMatchTheClientTest {
    private static final Path CLIENT_LIMITS = Path.of("..", "frontend", "src", "services", "textLimits.js");
    private static final Pattern CONSTANT = Pattern.compile("export const (\\w+) = (\\d+);");

    private static Map<String, Integer> clientLimits() throws IOException {
        Map<String, Integer> limits = new HashMap<>();
        Matcher matcher = CONSTANT.matcher(Files.readString(CLIENT_LIMITS));
        while (matcher.find()) {
            limits.put(matcher.group(1), Integer.valueOf(matcher.group(2)));
        }
        return limits;
    }

    private static int serverMax(Class<?> request, String field) throws NoSuchFieldException {
        Size size = innermostSize(request.getDeclaredField(field).getAnnotatedType());
        assertThat(size).as("%s.%s carries no @Size", request.getSimpleName(), field).isNotNull();
        return size.max();
    }

    private static Size innermostSize(AnnotatedType type) {
        Size own = type.getAnnotation(Size.class);
        if (own != null) {
            return own;
        }
        if (type instanceof AnnotatedParameterizedType parameterized) {
            for (AnnotatedType argument : parameterized.getAnnotatedActualTypeArguments()) {
                Size nested = innermostSize(argument);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("every name and title the client caps is capped at the same length by the server")
    void namesAgree() throws Exception {
        int client = clientLimits().get("NAME_MAX_LENGTH");

        assertThat(Map.of(
                "CreateBoardRequest.name", serverMax(CreateBoardRequest.class, "name"),
                "PatchBoardRequest.name", serverMax(PatchBoardRequest.class, "name"),
                "CreateColumnRequest.name", serverMax(CreateColumnRequest.class, "name"),
                "PatchColumnRequest.name", serverMax(PatchColumnRequest.class, "name"),
                "CreateRowRequest.name", serverMax(CreateRowRequest.class, "name"),
                "PatchRowRequest.name", serverMax(PatchRowRequest.class, "name"),
                "CreateTaskRequest.title", serverMax(CreateTaskRequest.class, "title"),
                "PatchTaskRequest.title", serverMax(PatchTaskRequest.class, "title"),
                "CreateSubTaskRequest.title", serverMax(CreateSubTaskRequest.class, "title"),
                "PatchSubTaskRequest.title", serverMax(PatchSubTaskRequest.class, "title")))
                .allSatisfy((field, max) -> assertThat(max).as(field).isEqualTo(client));
    }

    @Test
    @DisplayName("descriptions and labels are capped at the same length on both sides")
    void descriptionsAndLabelsAgree() throws Exception {
        Map<String, Integer> client = clientLimits();

        assertThat(client.get("DESCRIPTION_MAX_LENGTH")).isEqualTo(CreateTaskRequest.DESCRIPTION_MAX_LENGTH);
        assertThat(client.get("LABEL_MAX_LENGTH")).isEqualTo(CreateTaskRequest.LABEL_MAX_LENGTH);
        assertThat(Map.of(
                "CreateTaskRequest.description", serverMax(CreateTaskRequest.class, "description"),
                "PatchTaskRequest.description", serverMax(PatchTaskRequest.class, "description"),
                "CreateSubTaskRequest.description", serverMax(CreateSubTaskRequest.class, "description"),
                "PatchSubTaskRequest.description", serverMax(PatchSubTaskRequest.class, "description")))
                .allSatisfy((field, max) -> assertThat(max).as(field).isEqualTo(CreateTaskRequest.DESCRIPTION_MAX_LENGTH));
        assertThat(serverMax(CreateTaskRequest.class, "labels")).isEqualTo(CreateTaskRequest.LABEL_MAX_LENGTH);
        assertThat(serverMax(PatchTaskRequest.class, "labels")).isEqualTo(CreateTaskRequest.LABEL_MAX_LENGTH);
    }
}
