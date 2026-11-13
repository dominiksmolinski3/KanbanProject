package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeCompressionTest {
    private static final Path TEMPLATE = Path.of("..", "frontend", "nginx", "default.conf.template");

    @Test
    @DisplayName("the edge compresses the bundle and the API's JSON")
    void textResponsesAreCompressed() throws IOException {
        assertThat(directive("gzip"))
                .as("nothing else on the path compresses: the API has no server.compression and the bundle "
                        + "is not pre-compressed, so without this every script and board read crosses the wire whole")
                .isEqualTo("on");
        assertThat(List.of(directive("gzip_types").split("\s+")))
                .as("nginx compresses only text/html unless a type is listed")
                .contains("application/javascript", "text/css", "application/json");
    }

    @Test
    @DisplayName("a request that arrived through the platform's ingress is still compressed")
    void proxiedRequestsAreCompressed() throws IOException {
        assertThat(directive("gzip_proxied"))
                .as("nginx skips compression for a request carrying a Via header unless this allows it")
                .isEqualTo("any");
        assertThat(directive("gzip_vary"))
                .as("a cache in front must not hand the compressed copy to a client that did not ask for it")
                .isEqualTo("on");
    }

    private static String directive(String name) throws IOException {
        assertThat(TEMPLATE).as("the edge template has moved or gone").isRegularFile();
        Matcher match = Pattern.compile("(?m)^\s*" + name + "\s+([^;]+);")
                .matcher(Files.readString(TEMPLATE, StandardCharsets.UTF_8));
        assertThat(match.find()).as("the edge config sets no %s", name).isTrue();
        return match.group(1).trim();
    }
}
