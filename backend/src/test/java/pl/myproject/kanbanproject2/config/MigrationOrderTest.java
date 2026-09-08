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

/**
 * Two branches that each add a migration have an order between them, and it is invisible to every
 * other guard here: the compiler cannot see it, {@link FlywayMigrationsMatchEntitiesTest} compares
 * names and not the order anything ran in, and a wrong-order merge produces a green build and a
 * contiguous {@code V1..Vn} on {@code main}. The trap is at deploy time on the revision <em>after</em>
 * the merge: Flyway has the higher version in its history, sees the lower one turn up pending, and
 * refuses (default {@code out-of-order=false}).
 *
 * <p>This test cannot see that trap either -- by the time both are on {@code main} the damage is a
 * property of what already ran against the deployed database, not of any file. What it <em>can</em>
 * do is fail the sloppy merge that produces it: a duplicated version, a gap, a file that does not
 * parse as {@code V<n>__<description>.sql}. Paired with the pull-request check in
 * {@code .github/workflows/migration-order.yml} -- which fails a PR that adds a migration numbered at
 * or below the highest version already on the base branch -- the order stops mattering, because a
 * branch that has fallen behind must renumber before it can merge.
 */
class MigrationOrderTest {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    /** Flyway's versioned-migration naming: {@code V<version>__<description>.sql}. */
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
