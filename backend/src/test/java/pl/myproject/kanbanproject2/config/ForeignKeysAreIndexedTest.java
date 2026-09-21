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

/**
 * Every foreign key in this schema has an index leading with its own column.
 *
 * <p>Postgres, unlike MySQL, creates no index for a foreign key constraint - it indexes the side
 * the key points at and leaves the referencing column bare. So each unindexed one is a sequential
 * scan on "which rows point at this", and a scan of the whole child table every time a referenced
 * row is deleted or updated. None of that is visible on a seeded board, no query fails, and
 * nothing in the application's own suite can see it: the entities are mapped correctly either way
 * and {@code FlywayMigrationsMatchEntitiesTest} compares columns, not access paths.
 *
 * <p>This is therefore the {@code DeadLetterAlertTest} shape applied to the schema - a rule that
 * lives in the migrations and is checked nowhere else. What it buys is not the twelve-odd indexes
 * {@code V19} added, which are a one-off, but the next foreign key: adding one now means adding
 * its index in the same migration or failing the build, rather than discovering it on a board with
 * data on it.
 *
 * <p>Two parsing rules are load-bearing and neither is obvious:
 *
 * <ul>
 *   <li><b>Only the leading column of an index counts.</b> A composite index on
 *       {@code (board_id, user_id)} - which is what {@code board_members}' primary key is - serves
 *       lookups by {@code board_id} and cannot serve lookups by {@code user_id}. Reading a
 *       composite index as covering every column in it is precisely the mistake that left the
 *       membership check unindexed.</li>
 *   <li><b>A partial index covers nothing.</b> {@code ux_board_invitations_pending} is on
 *       {@code (board_id, email) WHERE status = 'PENDING'}, so it cannot answer a lookup by board
 *       that ignores status - which is the one {@code BoardService.deleteBoard} makes.</li>
 * </ul>
 */
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

        // A parser that has quietly stopped matching reports no foreign keys and therefore no
        // uncovered ones, which is indistinguishable from a schema with none. These are three
        // shapes the corpus actually contains: an ALTER ... ADD CONSTRAINT (V1), an inline
        // column-level REFERENCES (V15) and an ADD COLUMN ... REFERENCES (V18).
        assertThat(schema.foreignKeys())
                .contains(new Column("task", "column_id"),
                          new Column("task_activity", "actor_id"),
                          new Column("chat_messages", "board_id"))
                .hasSizeGreaterThan(15);

        assertThat(schema.indexedLeadingColumns())
                .contains(new Column("board_members", "board_id"),  // the composite primary key
                          new Column("users", "email"),             // a column-level UNIQUE
                          new Column("task", "board_id"));          // a plain CREATE INDEX
    }

    @Test
    @DisplayName("the check fails on an unindexed foreign key, a composite that leads elsewhere, and a partial index")
    void theCheckFires() {
        // The control. A guard that cannot fire is indistinguishable from a schema with nothing to
        // find, and the three cases below are the three this one exists to tell apart.
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

    // ------------------------------------------------------------------------------- the schema

    /** One column of one table. Identifiers are folded to lower case, as Postgres folds them. */
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

    /**
     * What the migrations declare, replayed in order: which columns carry a foreign key, and which
     * columns an index leads with. Not a SQL implementation - it understands exactly the statement
     * shapes this corpus uses, and an unrecognised one contributes nothing rather than failing,
     * which is what {@link #theParserSeesTheSchema()} exists to catch.
     */
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

        /** The foreign keys no index leads with, which is the whole point of the class. */
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
            // " where " in a CREATE INDEX can only be the predicate of a partial index, and a
            // partial index answers no lookup that does not carry the predicate too.
            if (createIndex.find() && !statement.contains(" where ")) {
                indexed.add(new Column(createIndex.group(1), createIndex.group(2)));
            }
        }

        private static void readTableBody(String table, String body, Set<Column> foreignKeys, Set<Column> indexed) {
            for (String item : splitTopLevel(body)) {
                Matcher keyList = KEY_LIST.matcher(item);
                if (keyList.find()) {
                    // A table-level PRIMARY KEY (a, b) or UNIQUE (a, b): an index leading with a.
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

        /** A single column's definition: its type and whatever modifiers follow it. */
        private static void readColumnDefinition(String table, String name, String rest,
                                                 Set<Column> foreignKeys, Set<Column> indexed) {
            Column column = new Column(table, name);
            if (REFERENCES.matcher(rest).find()) {
                foreignKeys.add(column);
            }
            // Either one creates a unique index on the column by itself.
            if (OWN_INDEX.matcher(rest).find()) {
                indexed.add(column);
            }
        }

        /** Everything between a CREATE TABLE's outermost parentheses. */
        private static String body(String statement) {
            int open = statement.indexOf('(');
            int close = statement.lastIndexOf(')');
            return open >= 0 && close > open ? statement.substring(open + 1, close).trim() : "";
        }

        /** Split on the commas that separate a table's items, not the ones inside {@code varchar(255)}. */
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

        /**
         * One SQL document as a list of statements, comments removed, whitespace collapsed and
         * identifiers folded to lower case - so a statement split across five lines reads the same
         * as one written on a single line.
         */
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

    /** The migration files, in the order Flyway would apply them. */
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
