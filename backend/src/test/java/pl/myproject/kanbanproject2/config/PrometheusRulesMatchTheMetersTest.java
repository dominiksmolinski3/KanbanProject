package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusRulesMatchTheMetersTest {
    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final Path OBSERVABILITY = Path.of("..", "observability");
    private static final Path RULES = OBSERVABILITY.resolve(Path.of("prometheus", "rules"));
    private static final Path DASHBOARDS = OBSERVABILITY.resolve(Path.of("grafana", "dashboards"));

    private static final Pattern DECLARED = Pattern.compile(
            "static final String (\\w+)\\s*=\\s*\"(kanban\\.[a-z0-9_.]+)\"");

    private static final Pattern NAMED = Pattern.compile("\\bkanban_[a-z0-9_]+");

    @Test
    @DisplayName("every kanban_* series a rule or dashboard reads is a meter the code registers, as Prometheus names it")
    void everyReadSeriesExists() {
        Set<String> exported = new TreeSet<>(exportedNames().values());

        assertThat(readNames())
                .as("these rules or panels read a series nothing sends: a meter was renamed, or a counter "
                        + "lost the _total Prometheus adds to it. Series the code exports: %s", exported)
                .isSubsetOf(exported);
    }

    @Test
    @DisplayName("every meter is a counter or a gauge by the name of its constant")
    void everyMeterHasAKnownKind() {
        assertThat(declaredConstants().values())
                .as("a constant ending in neither _COUNTER nor _GAUGE leaves this guard unable to tell "
                        + "whether Prometheus adds _total to it")
                .allMatch(constant -> constant.endsWith("_COUNTER") || constant.endsWith("_GAUGE"));
    }

    @Test
    @DisplayName("the readers are still reading something")
    void theParsersStillMatch() {
        assertThat(readNames()).contains("kanban_mail_outbox_dead_letters_total", "kanban_mail_outbox_pending");
        assertThat(exportedNames()).containsEntry("kanban.mail.outbox.dead_letters", "kanban_mail_outbox_dead_letters_total")
                .containsEntry("kanban.mail.outbox.pending", "kanban_mail_outbox_pending");
        assertThat(NAMED.matcher("rate(kanban_mail_outbox_dead_letters[5m])").find())
                .as("control: a counter read without _total is a name this guard has to catch")
                .isTrue();
        assertThat(exportedNames()).doesNotContainValue("kanban_mail_outbox_dead_letters");
    }

    private static Map<String, String> exportedNames() {
        Map<String, String> names = new TreeMap<>();
        declaredConstants().forEach((meter, constant) -> {
            String prometheus = meter.replace('.', '_');
            names.put(meter, constant.endsWith("_COUNTER") ? prometheus + "_total" : prometheus);
        });
        return names;
    }

    private static Map<String, String> declaredConstants() {
        Map<String, String> constants = new TreeMap<>();
        for (Path file : files(SOURCES, ".java")) {
            Matcher matcher = DECLARED.matcher(read(file));
            while (matcher.find()) {
                constants.put(matcher.group(2), matcher.group(1));
            }
        }
        return constants;
    }

    private static Set<String> readNames() {
        Set<String> names = new TreeSet<>();
        Stream.concat(files(RULES, ".yml").stream(), files(DASHBOARDS, ".json").stream())
                .forEach(file -> NAMED.matcher(read(file)).results().forEach(match -> names.add(match.group())));
        return names;
    }

    private static List<Path> files(Path root, String extension) {
        assertThat(root).as("%s has moved or gone; this guard reads it by path", root).isDirectory();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(extension))
                    .filter(path -> !path.toString().contains(Path.of("rules", "tests").toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
