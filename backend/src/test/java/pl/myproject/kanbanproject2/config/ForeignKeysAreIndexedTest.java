package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ForeignKeysAreIndexedTest {
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    @Test
    @DisplayName("every foreign key column has an index leading with it")
    void everyForeignKeyIsIndexed() {
        Schema schema = Schema.of(migrationsInOrder());

        assertThat(schema.uncoveredForeignKeys())
                .as("foreign key columns with no index leading with them. Postgres indexes the "
                        + "referenced side only, so each of these is a sequential scan on the "
                        + "child table - add an index in the same migration that adds the key")
                .isEmpty();
    }

    @Test
    @DisplayName("the parser reads the schema it is pointed at rather than an empty one")
    void theParserSeesTheSchema() {
        Schema schema = Schema.of(migrationsInOrder());

        assertThat(schema.foreignKeys())
                .contains(new Column("task", "column_id"),
                          new Column("task_activity", "actor_id"),
                          new Column("chat_messages", "board_id"))
                .hasSizeGreaterThan(15);

        assertThat(schema.indexedLeadingColumns())
                .contains(new Column("board_members", "board_id"),
                          new Column("users", "email"),
                          new Column("task", "board_id"));
    }

    @Test
    @DisplayName("the check fails on an unindexed foreign key, a composite that leads elsewhere, and a partial index")
    void theCheckFires() {
        assertThat(Schema.of(List.of("""
                create table parent (id integer primary key);
                create table child (id integer primary key, parent_id integer references parent (id));
                """)).uncoveredForeignKeys())
                .as("a foreign key with no index at all")
                .containsExactly(new Column("child", "parent_id"));

        assertThat(Schema.of(List.of("""
                create table parent (id integer primary key);
                create table child (id integer primary key, parent_id integer references parent (id));
                create index ix_child on child (id, parent_id);
                """)).uncoveredForeignKeys())
                .as("an index containing the column but not leading with it")
                .containsExactly(new Column("child", "parent_id"));

        assertThat(Schema.of(List.of("""
                create table parent (id integer primary key);
                create table child (id integer primary key, parent_id integer references parent (id), state varchar(8));
                create index ix_child on child (parent_id) where state = 'OPEN';
                """)).uncoveredForeignKeys())
                .as("a partial index, which answers no lookup that ignores its predicate")
                .containsExactly(new Column("child", "parent_id"));

        assertThat(Schema.of(List.of("""
                create table parent (id integer primary key);
                create table child (id integer primary key, parent_id integer references parent (id));
                create index ix_child on child (parent_id, id);
                """)).uncoveredForeignKeys())
                .as("a composite index that does lead with the column is coverage")
                .isEmpty();
    }

    private record Column(String table, String name) implements Comparable<Column> {
        @Override
        public String toString() {
            return table + " (" + name + ")";
        }

        @Override
        public int compareTo(Column other) {
            return Comparator.comparing(Column::table).thenComparing(Column::name).compare(this, other);
        }
    }

    private record Schema(Set<Column> foreignKeys, Set<Column> indexedLeadingColumns) {
        private static final Pattern CREATE_TABLE =
                Pattern.compile("^create table (?:if not exists )?(\\w+) ?\\(");
        private static final Pattern ADD_FOREIGN_KEY =
                Pattern.compile("^alter table (?:if exists )?(\\w+) add constraint \\S+ foreign key \\((\\w+)");
        private static final Pattern ADD_UNIQUE_OR_PRIMARY =
                Pattern.compile("^alter table (?:if exists )?(\\w+) add constraint \\S+ (?:unique|primary key) \\((\\w+)");
        private static final Pattern ADD_COLUMN =
                Pattern.compile("^alter table (?:if exists )?(\\w+) add column (?:if not exists )?(\\w+) (.*)$");
        private static final Pattern DROP_COLUMN =
                Pattern.compile("^alter table (?:if exists )?(\\w+) drop column (?:if exists )?(\\w+)");
        private static final Pattern DROP_TABLE =
                Pattern.compile("^drop table (?:if exists )?(\\w+)");
        private static final Pattern CREATE_INDEX =
                Pattern.compile("^create (?:unique )?index (?:concurrently )?(?:if not exists )?\\S+ on (\\w+) ?\\(\\s*(\\w+)");
        private static final Pattern REFERENCES = Pattern.compile("\\breferences\\b");
        private static final Pattern OWN_INDEX = Pattern.compile("\\b(?:primary key|unique)\\b");
        private static final Pattern KEY_LIST = Pattern.compile("^(?:constraint \\S+ )?(?:primary key|unique) \\(\\s*(\\w+)");

        static Schema of(List<String> sqlDocuments) {
            Set<Column> foreignKeys = new LinkedHashSet<>();
            Set<Column> indexed = new LinkedHashSet<>();

            for (String document : sqlDocuments) {
                for (String statement : statements(document)) {
                    apply(statement, foreignKeys, indexed);
                }
            }
            return new Schema(foreignKeys, indexed);
        }

        Set<Column> uncoveredForeignKeys() {
            Set<Column> uncovered = new TreeSet<>(foreignKeys);
            uncovered.removeAll(indexedLeadingColumns);
            return uncovered;
        }

        private static void apply(String statement, Set<Column> foreignKeys, Set<Column> indexed) {
            Matcher createTable = CREATE_TABLE.matcher(statement);
            if (createTable.find()) {
                readTableBody(createTable.group(1), body(statement), foreignKeys, indexed);
                return;
            }

            Matcher addForeignKey = ADD_FOREIGN_KEY.matcher(statement);
            if (addForeignKey.find()) {
                foreignKeys.add(new Column(addForeignKey.group(1), addForeignKey.group(2)));
                return;
            }

            Matcher addKey = ADD_UNIQUE_OR_PRIMARY.matcher(statement);
            if (addKey.find()) {
                indexed.add(new Column(addKey.group(1), addKey.group(2)));
                return;
            }

            Matcher addColumn = ADD_COLUMN.matcher(statement);
            if (addColumn.find()) {
                readColumnDefinition(addColumn.group(1), addColumn.group(2), addColumn.group(3), foreignKeys, indexed);
                return;
            }

            Matcher dropColumn = DROP_COLUMN.matcher(statement);
            if (dropColumn.find()) {
                Column dropped = new Column(dropColumn.group(1), dropColumn.group(2));
                foreignKeys.remove(dropped);
                indexed.remove(dropped);
                return;
            }

            Matcher dropTable = DROP_TABLE.matcher(statement);
            if (dropTable.find()) {
                foreignKeys.removeIf(column -> column.table().equals(dropTable.group(1)));
                indexed.removeIf(column -> column.table().equals(dropTable.group(1)));
                return;
            }

            Matcher createIndex = CREATE_INDEX.matcher(statement);
            if (createIndex.find() && !statement.contains(" where ")) {
                indexed.add(new Column(createIndex.group(1), createIndex.group(2)));
            }
        }

        private static void readTableBody(String table, String body, Set<Column> foreignKeys, Set<Column> indexed) {
            for (String item : splitTopLevel(body)) {
                Matcher keyList = KEY_LIST.matcher(item);
                if (keyList.find()) {
                    indexed.add(new Column(table, keyList.group(1)));
                    continue;
                }
                if (item.startsWith("constraint ") || item.startsWith("check ") || item.startsWith("foreign key ")) {
                    continue;
                }
                int firstSpace = item.indexOf(' ');
                if (firstSpace > 0) {
                    readColumnDefinition(table, item.substring(0, firstSpace),
                            item.substring(firstSpace + 1), foreignKeys, indexed);
                }
            }
        }

        private static void readColumnDefinition(String table, String name, String rest,
                                                 Set<Column> foreignKeys, Set<Column> indexed) {
            Column column = new Column(table, name);
            if (REFERENCES.matcher(rest).find()) {
                foreignKeys.add(column);
            }
            if (OWN_INDEX.matcher(rest).find()) {
                indexed.add(column);
            }
        }

        private static String body(String statement) {
            int open = statement.indexOf('(');
            int close = statement.lastIndexOf(')');
            return open >= 0 && close > open ? statement.substring(open + 1, close).trim() : "";
        }

        private static List<String> splitTopLevel(String body) {
            List<String> items = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int depth = 0;
            boolean quoted = false;
            for (char character : body.toCharArray()) {
                if (character == '\'') {
                    quoted = !quoted;
                } else if (!quoted && character == '(') {
                    depth++;
                } else if (!quoted && character == ')') {
                    depth--;
                } else if (!quoted && character == ',' && depth == 0) {
                    items.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
                current.append(character);
            }
            if (!current.toString().isBlank()) {
                items.add(current.toString().trim());
            }
            return items;
        }

        private static List<String> statements(String sql) {
            String stripped = sql.replaceAll("--[^\\n]*", " ")
                    .replaceAll("\\s+", " ")
                    .toLowerCase();
            List<String> statements = new ArrayList<>();
            for (String statement : stripped.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    statements.add(trimmed);
                }
            }
            return statements;
        }
    }

    private static List<String> migrationsInOrder() {
        Pattern version = Pattern.compile("V(\\d+)__");
        try (var files = Files.list(MIGRATIONS)) {
            List<Path> ordered = files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(path -> {
                        Matcher matcher = version.matcher(path.getFileName().toString());
                        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
                    }))
                    .toList();

            assertThat(ordered)
                    .as("no migrations found under %s - this test is reading the wrong directory "
                            + "rather than looking at a schema with no tables in it", MIGRATIONS.toAbsolutePath())
                    .isNotEmpty();

            List<String> documents = new ArrayList<>();
            for (Path path : ordered) {
                documents.add(Files.readString(path));
            }
            return documents;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + MIGRATIONS.toAbsolutePath(), e);
        }
    }
}
