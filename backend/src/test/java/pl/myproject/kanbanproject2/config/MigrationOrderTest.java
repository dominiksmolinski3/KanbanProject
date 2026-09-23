package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationOrderTest {
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    private static final Pattern VERSIONED = Pattern.compile("V(\\d+)__[a-z0-9]+(?:_[a-z0-9]+)*\\.sql");

    @Test
    @DisplayName("every migration file parses as V<n>__<snake_case_description>.sql")
    void namesAreWellFormed() {
        List<String> malformed = migrationFiles()
                .map(p -> p.getFileName().toString())
                .filter(name -> !VERSIONED.matcher(name).matches())
                .toList();

        assertThat(malformed)
                .as("files under db/migration that are not V<n>__<lower_snake_case>.sql")
                .isEmpty();
    }

    @Test
    @DisplayName("versions are 1..N with no gap and no duplicate")
    void versionsAreContiguousAndUnique() {
        List<Integer> versions = migrationFiles()
                .map(p -> p.getFileName().toString())
                .map(VERSIONED::matcher)
                .filter(Matcher::matches)
                .map(m -> Integer.parseInt(m.group(1)))
                .sorted()
                .toList();

        assertThat(versions)
                .as("no migration files found -- the path or the naming has moved")
                .isNotEmpty();

        assertThat(versions)
                .as("duplicate migration version -- two branches added the same V<n> and the merge kept both")
                .doesNotHaveDuplicates();

        List<Integer> expected = Stream.iterate(1, n -> n + 1).limit(versions.size()).toList();
        assertThat(versions)
                .as("migration versions must run 1..N with no gap; a gap is a rename that stopped halfway")
                .isEqualTo(expected);
    }

    private static Stream<Path> migrationFiles() {
        try {
            return Files.list(MIGRATIONS).filter(p -> p.getFileName().toString().endsWith(".sql"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + MIGRATIONS.toAbsolutePath(), e);
        }
    }
}
