package pl.myproject.kanbanproject2.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BoardChannelBackfillDatabaseTest {

    private final String schema = "bf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        String[] connection = DatabaseTestConfiguration.connection();
        dataSource = new DriverManagerDataSource(connection[0], connection[1], connection[2]);
        dataSource.setSchema(schema);
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void tearDown() {
        new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private int user(String name) {
        return jdbc.queryForObject("insert into users (email, enabled, name, password, locale) values (?, true, ?, 'x', 'en') "
                + "returning id", Integer.class, name + "@example.test", name);
    }

    @Test
    @DisplayName("boards that exist before the migration each get a channel seating their owner, members and viewers")
    void existingBoardsGetChannels() {
        flyway("32").migrate();
        int owner = user("owner");
        int editor = user("editor");
        int viewer = user("viewer");
        int ownerless = jdbc.queryForObject(
                "insert into boards (name, created_at) values ('Unclaimed', now()) returning id", Integer.class);
        int board = jdbc.queryForObject(
                "insert into boards (name, owner_id, created_at) values ('Work', ?, now()) returning id",
                Integer.class, owner);
        jdbc.update("insert into board_members (board_id, user_id, role) values (?, ?, 'MEMBER')", board, owner);
        jdbc.update("insert into board_members (board_id, user_id, role) values (?, ?, 'MEMBER')", board, editor);
        jdbc.update("insert into board_members (board_id, user_id, role) values (?, ?, 'VIEWER')", board, viewer);

        flyway("33").migrate();

        assertThat(jdbc.queryForObject("select count(*) from conversations where kind = 'BOARD'", Integer.class))
                .isEqualTo(jdbc.queryForObject("select count(*) from boards", Integer.class));
        Long channel = jdbc.queryForObject("select id from conversations where board_id = ?", Long.class, board);
        List<Map<String, Object>> seats = jdbc.queryForList(
                "select user_id, role, state, joined_seq from conversation_members where conversation_id = ? "
                        + "order by user_id", channel);
        assertThat(seats).extracting(seat -> seat.get("user_id"), seat -> seat.get("role"), seat -> seat.get("state"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(owner, "ADMIN", "ACTIVE"),
                        org.assertj.core.groups.Tuple.tuple(editor, "MEMBER", "ACTIVE"),
                        org.assertj.core.groups.Tuple.tuple(viewer, "VIEWER", "ACTIVE"));
        assertThat(jdbc.queryForObject("select epoch from conversations where board_id = ?", Long.class, board))
                .isZero();
        Long unclaimed = jdbc.queryForObject("select id from conversations where board_id = ?", Long.class, ownerless);
        assertThat(jdbc.queryForObject("select count(*) from conversation_members where conversation_id = ?",
                Integer.class, unclaimed)).isZero();
    }

    @Test
    @DisplayName("an owner missing from the board's member list is still seated, and every channel gets its own group id")
    void ownerOutsideTheMemberList() {
        flyway("32").migrate();
        int owner = user("owner");
        jdbc.update("insert into boards (name, owner_id, created_at) values ('A', ?, now())", owner);
        jdbc.update("insert into boards (name, owner_id, created_at) values ('B', ?, now())", owner);

        int boards = jdbc.queryForObject("select count(*) from boards", Integer.class);

        flyway("33").migrate();

        assertThat(jdbc.queryForObject("select count(*) from conversation_members where role = 'ADMIN'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(distinct group_id) from conversations", Integer.class))
                .isEqualTo(boards);
        assertThat(jdbc.queryForObject("select length(group_id) from conversations limit 1", Integer.class))
                .isEqualTo(16);
    }
}
