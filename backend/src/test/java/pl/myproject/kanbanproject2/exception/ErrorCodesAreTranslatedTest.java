package pl.myproject.kanbanproject2.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodesAreTranslatedTest {
    private static final Path CLIENT_LOCALES = Path.of("..", "frontend", "public", "locales");
    private static final Path HANDLER = Path.of("src", "main", "java", "pl", "myproject", "kanbanproject2",
            "exception", "GlobalExceptionHandler.java");
    private static final Path EDGE = Path.of("..", "frontend", "nginx", "default.conf.template");

    private static final Pattern HANDLER_CODE = Pattern.compile("ErrorResponse\\.of\\(\\s*\"([A-Z_]+)\"");
    private static final Pattern EDGE_CODE = Pattern.compile("\\\\?\"code\\\\?\"\\s*:\\s*\\\\?\"([A-Z_]+)");

    @Test
    @DisplayName("every error code the server or the edge can send has a sentence in every client bundle")
    void everyCodeIsTranslatedEverywhere() throws IOException {
        Set<String> codes = codes();
        assertThat(codes)
                .as("literal codes in the handler - a parser that stopped matching would pass everything below")
                .contains("VALIDATION_ERROR", "INTERNAL_ERROR", "INVALID_CREDENTIALS", "TOO_MANY_REQUESTS");

        ObjectMapper json = new ObjectMapper();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> languages = Files.list(CLIENT_LOCALES)) {
            for (Path language : languages.filter(Files::isDirectory).toList()) {
                JsonNode errors = json.readTree(language.resolve("translation.json").toFile()).path("errors");
                for (String key : List.of("generic", "status.tooLarge", "status.server")) {
                    if (!errors.at("/" + key.replace('.', '/')).isTextual()) {
                        missing.add(language.getFileName() + " -> errors." + key);
                    }
                }
                for (String code : codes) {
                    if (!errors.path("codes").path(code).isTextual()) {
                        missing.add(language.getFileName() + " -> errors.codes." + code);
                    }
                }
            }
        }

        assertThat(missing)
                .as("a code with no sentence falls back to a generic one, which hides what went wrong")
                .isEmpty();
    }

    private static Set<String> codes() throws IOException {
        Set<String> codes = new TreeSet<>();
        Arrays.stream(ExceptionIdentifier.values()).map(Enum::name).forEach(codes::add);
        collect(HANDLER_CODE.matcher(Files.readString(HANDLER, StandardCharsets.UTF_8)), codes);
        collect(EDGE_CODE.matcher(Files.readString(EDGE, StandardCharsets.UTF_8)), codes);
        return codes;
    }

    private static void collect(Matcher matcher, Set<String> into) {
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }
}
