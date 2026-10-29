package pl.myproject.kanbanproject2.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(DatabaseTestConfiguration.class)
class MigrationsBuildTheMappedSchemaTest {

    private static final Pattern VERSION = Pattern.compile("V(\\d+)__.*\\.sql");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("every migration applies to an empty Postgres, and Hibernate validates every entity against the result")
    void theMigratedSchemaIsTheMappedOne() throws IOException {
        int highest;
        try (Stream<Path> files = Files.list(Path.of("src", "main", "resources", "db", "migration"))) {
            highest = files.map(file -> VERSION.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .mapToInt(match -> Integer.parseInt(match.group(1)))
                    .max()
                    .orElseThrow();
        }

        assertThat(flyway.info().current().getVersion().getVersion())
                .as("the context only starts once ddl-auto=validate accepts the migrated schema")
                .isEqualTo(String.valueOf(highest));
        assertThat(jdbc.queryForObject("select current_schema()", String.class))
                .isEqualTo(DatabaseTestConfiguration.SCHEMA);
    }

    @Test
    @DisplayName("every moment the schema stores carries its zone")
    void noTimestampIsZoneless() {
        var zoneless = jdbc.queryForList("""
                select table_name || '.' || column_name from information_schema.columns
                where table_schema = current_schema() and data_type = 'timestamp without time zone'
                  and table_name <> 'flyway_schema_history'
                order by 1""", String.class);
        var zoned = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = current_schema() and data_type = 'timestamp with time zone'""", Integer.class);

        assertThat(zoned).as("the query sees the schema's timestamps at all").isGreaterThan(10);
        assertThat(zoneless).isEmpty();
    }
}
