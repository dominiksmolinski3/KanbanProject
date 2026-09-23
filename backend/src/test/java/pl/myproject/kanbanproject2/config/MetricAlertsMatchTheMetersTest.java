package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MetricAlertsMatchTheMetersTest {
    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Path AGENT_CONFIG = Path.of("applicationinsights.json");
    private static final Path DIAGNOSTICS = Path.of("..", "terraform", "modules", "diagnostics", "main.tf");

    private static final Pattern DECLARED = Pattern.compile("\"(kanban\\.[a-z0-9_.]+)\"");

    private static final Pattern ASKED_FOR = Pattern.compile(
            "(?:Name\\s*==\\s*|metric\\s*=\\s*)\"(kanban_[a-z0-9_]+)\"");

    private static final Pattern FILTER = Pattern.compile("\"metricNames\"\\s*:\\s*\\[\\s*\"([^\"]+)\"");

    @Test
    @DisplayName("every metric an alert reads is a meter the code registers, under the agent's spelling")
    void everyAlertedMetricExists() {
        Set<String> exported = new TreeSet<>();
        declaredMeters().forEach(name -> exported.add(name.replace('.', '_')));

        assertThat(alertedMetrics())
                .as("these alerts watch a series nothing sends; the meter was renamed or never existed. "
                        + "Meters the code registers, as the agent exports them: %s", exported)
                .isSubsetOf(exported);
    }

    @Test
    @DisplayName("the agent's filter lets every alerted metric through")
    void theFilterKeepsWhatTheAlertsRead() throws IOException {
        var filter = FILTER.matcher(Files.readString(AGENT_CONFIG, StandardCharsets.UTF_8));
        assertThat(filter.find())
                .as("applicationinsights.json no longer has the metricNames exclude this reads")
                .isTrue();
        Pattern excluded = Pattern.compile(filter.group(1).replace("\\\\", "\\"));

        for (String metric : alertedMetrics()) {
            assertThat(excluded.matcher(metric).matches())
                    .as("the agent drops %s before it leaves the container, so its alert can never fire", metric)
                    .isFalse();
        }
        assertThat(excluded.matcher("jvm_memory_used").matches())
                .as("the filter has stopped excluding Spring's own meters")
                .isTrue();
    }

    @Test
    @DisplayName("the readers are still reading something")
    void theParsersStillMatch() {
        assertThat(alertedMetrics()).contains("kanban_mail_outbox_dead_letters", "kanban_mail_outbox_pending");
        assertThat(declaredMeters()).contains("kanban.mail.outbox.dead_letters", "kanban.mail.delivery.undelivered");
    }

    private static Set<String> alertedMetrics() {
        Set<String> names = new TreeSet<>();
        ASKED_FOR.matcher(read(DIAGNOSTICS)).results().forEach(match -> names.add(match.group(1)));
        return names;
    }

    private static Set<String> declaredMeters() {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> DECLARED.matcher(read(path)).results()
                            .forEach(match -> names.add(match.group(1))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    private static String read(Path path) {
        assertThat(path).as("%s has moved or gone; this guard reads it by path", path).exists();
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
