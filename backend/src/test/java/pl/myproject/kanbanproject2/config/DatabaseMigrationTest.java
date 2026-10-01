package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMigrationTest {
    private static final Path API_APP = Path.of("..", "terraform", "modules", "api_app", "main.tf");

    private static String block(String source, String header) {
        int start = source.indexOf(header);
        assertThat(start).as("%s not found in %s", header, API_APP).isNotNegative();
        int depth = 0;
        for (int i = source.indexOf('{', start); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + header);
    }

    private static Pattern env(String name, String value) {
        return Pattern.compile("name\\s*=\\s*\"" + name + "\"\\s*value\\s*=\\s*\"" + Pattern.quote(value) + "\"");
    }

    @Test
    @DisplayName("only the flag asks for a migration")
    void onlyTheFlagAsks() {
        assertThat(DatabaseMigration.requested(new String[]{"--migrate-only"})).isTrue();
        assertThat(DatabaseMigration.requested(new String[]{"--server.port=8081", "--migrate-only"})).isTrue();
        assertThat(DatabaseMigration.requested(new String[]{})).isFalse();
        assertThat(DatabaseMigration.requested(new String[]{"--migrate"})).isFalse();
    }

    @Test
    @DisplayName("the migration job runs the jar in migrate-only mode as the schema owner")
    void theJobMigratesAsTheOwner() throws IOException {
        String job = block(Files.readString(API_APP), "resource \"azurerm_container_app_job\" \"migrate\"");

        assertThat(job).contains("\"" + DatabaseMigration.FLAG + "\"");
        assertThat(job).containsPattern(env("DB_MIGRATION_INIT_SQL", "SET ROLE kanban_owner"));
    }

    @Test
    @DisplayName("the API does not migrate, because its role cannot")
    void theApiDoesNotMigrate() throws IOException {
        String api = block(Files.readString(API_APP), "resource \"azurerm_container_app\" \"main\"");

        assertThat(api).containsPattern(env("DB_MIGRATE_ON_STARTUP", "false"));
        assertThat(api).doesNotContain("DB_MIGRATION_INIT_SQL");
        assertThat(api).contains("terraform_data.migrate");
    }
}
