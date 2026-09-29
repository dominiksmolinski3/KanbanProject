package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class QueryStringsStayOutOfLogsTest {
    private static final Path TEMPLATE = Path.of("..", "frontend", "nginx", "default.conf.template");

    private static final Path AGENT_CONFIG = Path.of("applicationinsights.json");

    private static final Pattern EDGE_FORMAT = Pattern.compile("log_format\\s+edge([^;]*);");

    @Test
    @DisplayName("the edge access log records the path without its query string")
    void theEdgeLogDropsTheQuery() throws IOException {
        Matcher format = EDGE_FORMAT.matcher(Files.readString(TEMPLATE, StandardCharsets.UTF_8));

        assertThat(format.find()).as("the `edge` log_format is gone or has stopped parsing").isTrue();
        assertThat(format.group(1))
                .as("the delivery-report webhook authenticates with ?key=, so a log_format that "
                        + "carries the query writes that credential into Log Analytics on every call")
                .doesNotContainPattern("\\$(request|request_uri|args|query_string|arg_\\w+)\\b")
                .contains("$edge_path");
    }

    @Test
    @DisplayName("the agent records no request telemetry for the delivery-report webhook")
    void theAgentSkipsTheWebhook() throws IOException {
        assertThat(Files.readString(AGENT_CONFIG, StandardCharsets.UTF_8).replaceAll("\\s+", ""))
                .as("request telemetry stores the full URL, key included, in AppRequests")
                .contains("\"key\":\"url.path\",\"value\":\"/api/mail/delivery-reports\"");
    }
}
