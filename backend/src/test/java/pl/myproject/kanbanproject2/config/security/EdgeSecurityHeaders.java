package pl.myproject.kanbanproject2.config.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

final class EdgeSecurityHeaders {
    static final Path SNIPPET = Path.of("..", "frontend", "nginx", "security-headers.conf");

    static final Pattern ADD_HEADER =
            Pattern.compile("^\\s*add_header\\s+(\\S+)\\s+\"([^\"]*)\"(\\s+always)?\\s*;", Pattern.MULTILINE);

    private EdgeSecurityHeaders() {
    }

    static Map<String, String> read() {
        Map<String, String> headers = new LinkedHashMap<>();
        Matcher matcher = ADD_HEADER.matcher(source());
        while (matcher.find()) {
            headers.put(matcher.group(1), matcher.group(2));
        }
        assertThat(headers)
                .as("no add_header directives were found in %s, which means this is reading the "
                        + "wrong file rather than that the edge sends nothing", SNIPPET)
                .isNotEmpty();
        return headers;
    }

    static String contentSecurityPolicy() {
        String policy = read().get("Content-Security-Policy");
        assertThat(policy).as("%s no longer sets a Content-Security-Policy", SNIPPET).isNotBlank();
        return policy;
    }

    static String source() {
        assertThat(SNIPPET).as("%s has moved or gone", SNIPPET).isRegularFile();
        try {
            return Files.readString(SNIPPET, StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }
}
