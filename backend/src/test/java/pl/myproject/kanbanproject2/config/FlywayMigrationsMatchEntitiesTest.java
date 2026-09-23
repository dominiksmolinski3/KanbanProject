package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationsMatchEntitiesTest {
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    private static final Pattern ADDED_COLUMN = Pattern.compile(
            "alter\\s+table\\s+(?:if\\s+exists\\s+)?(\\S+?)\\s+add\\s+(?:column\\s+)?(?!constraint\\b)(\\S+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DROPPED_TABLE = Pattern.compile(
            "drop\\s+table\\s+(?:if\\s+exists\\s+)?(\\S+?)\\s*[;\\s]", Pattern.CASE_INSENSITIVE);

    private static String allMigrations() {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            List<Path> sorted = files
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
            var joined = new StringBuilder();
            for (Path file : sorted) {
                joined.append(Files.readString(file)).append('\n');
            }
            return joined.toString();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + MIGRATIONS.toAbsolutePath(), e);
        }
    }

    private static Set<String> migrationTables(String sql) {
        var tables = SchemaDdl.tableNames(sql);
        DROPPED_TABLE.matcher(sql).results()
                .map(m -> m.group(1).replace("\"", "").toLowerCase(Locale.ROOT))
                .forEach(tables::remove);
        return tables;
    }

    private static Set<String> migrationColumns(String sql) {
        var columns = new TreeSet<>(SchemaDdl.columnNames(sql));
        ADDED_COLUMN.matcher(sql).results().forEach(m -> {
            String table = m.group(1).replace("\"", "").toLowerCase(Locale.ROOT);
            String column = m.group(2).replace("\"", "").replace(",", "").toLowerCase(Locale.ROOT);
            columns.add(table + "." + column);
        });
        return columns;
    }

    @Test
    @DisplayName("there is a migration directory, and it is not empty")
    void migrationsExist() throws IOException {
        assertThat(MIGRATIONS).exists();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            assertThat(files.filter(p -> p.toString().endsWith(".sql")))
                    .as("Flyway migrations")
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("every entity has been scanned - the generator is not silently finding nothing")
    void entitiesAreFound() {
        assertThat(SchemaDdl.entityClasses())
                .as("entity classes")
                .hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("every table the entities need is created by a migration")
    void everyEntityTableIsMigrated() {
        Set<String> expected = SchemaDdl.tableNames(SchemaDdl.create());
        Set<String> actual = migrationTables(allMigrations());

        assertThat(actual)
                .as("tables created by migrations; add one for each entity that has no table yet")
                .containsAll(expected);
    }

    @Test
    @DisplayName("every column the entities need is created by a migration")
    void everyEntityColumnIsMigrated() {
        Set<String> expected = SchemaDdl.columnNames(SchemaDdl.create());
        Set<String> actual = migrationColumns(allMigrations());

        assertThat(actual)
                .as("columns created by migrations; a new entity field needs one, because "
                        + "ddl-auto=validate will not add it and will refuse to start without it")
                .containsAll(expected);
    }

    @Test
    @DisplayName("the migrations create nothing the entities do not ask for")
    void migrationsCreateNothingOrphaned() {
        Set<String> expected = SchemaDdl.tableNames(SchemaDdl.create());
        Set<String> actual = migrationTables(allMigrations());

        assertThat(actual)
                .as("a table no entity maps is either dead or a missing @Entity; "
                        + "flyway_schema_history is Flyway's own and is never created here")
                .isSubsetOf(expected);
    }
}
