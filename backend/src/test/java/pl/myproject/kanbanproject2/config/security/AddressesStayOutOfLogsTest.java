package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AddressesStayOutOfLogsTest {
    private static final Path SOURCES = Path.of("src", "main", "java");

    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(?:trace|debug|info|warn|error)\\((.*?)\\);",
            Pattern.DOTALL);

    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    private static final Pattern ADDRESS = Pattern.compile(
            "\\bget(?:Username|Email|Sender|RecipientId|PendingEmail)\\(\\)"
                    + "|\\b(?:username|email|address)\\b");

    @Test
    @DisplayName("no log statement is handed an email address")
    void logStatementsNameAccountsByIdOnly() throws IOException {
        List<String> offenders = new ArrayList<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher call = LOG_CALL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (call.find()) {
                    calls++;
                    String arguments = STRING_LITERAL.matcher(call.group(1)).replaceAll("\"\"");
                    if (ADDRESS.matcher(arguments).find()) {
                        offenders.add(file.getFileName() + ": " + call.group().replaceAll("\\s+", " "));
                    }
                }
            }
        }

        assertThat(calls).as("the log statement reader has stopped matching").isGreaterThan(50);
        assertThat(offenders)
                .as("a username here is the account's email address, and the logs are kept in Log Analytics "
                        + "far longer than the account may exist; log the account id instead")
                .isEmpty();
    }
}
