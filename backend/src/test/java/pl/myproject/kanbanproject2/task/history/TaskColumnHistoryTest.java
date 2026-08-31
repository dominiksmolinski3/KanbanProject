package pl.myproject.kanbanproject2.task.history;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.task.Task;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record of a card moving between stages, and the shape it reaches a client in.
 *
 * <p>Small, and worth pinning for one reason: the entity copies the column's <em>name</em> at the
 * moment of the move rather than reading it through the association. A stage that is renamed later
 * would otherwise rewrite history - every past move through it would report the new name, and a
 * stage that was deleted would report nothing at all. The mapper reads both, so it is the place the
 * distinction is visible.
 */
class TaskColumnHistoryTest {

    private static Column column(int id, String name) {
        var column = new Column();
        column.setId(id);
        column.setName(name);
        return column;
    }

    private static Task task(int id, String title) {
        var task = new Task();
        task.setId(id);
        task.setTitle(title);
        return task;
    }

    @Test
    @DisplayName("an entry copies the stage's name as it was, and stamps the time of the move")
    void anEntryCopiesTheNameAtTheTimeOfTheMove() {
        var before = LocalDateTime.now();
        var entry = new TaskColumnHistory(task(1, "Write the migration"), column(2, "Doing"));

        assertThat(entry.getColumnName()).isEqualTo("Doing");
        assertThat(entry.getHistoryOrder()).isZero();
        assertThat(entry.getChangedAt()).isBetween(before, LocalDateTime.now());
    }

    @Test
    @DisplayName("renaming the stage afterwards does not rewrite what the entry says")
    void renamingTheStageDoesNotRewriteHistory() {
        var stage = column(2, "Doing");
        var entry = new TaskColumnHistory(task(1, "Write the migration"), stage);

        stage.setName("In Progress");

        // The card moved into a stage called "Doing"; that it is called something else now is a
        // fact about the board today, not about the move.
        assertThat(entry.getColumnName()).isEqualTo("Doing");
    }

    @Test
    @DisplayName("the DTO carries the id and title of the task, so a client needs no second call")
    void theDtoCarriesTheTaskItDescribes() {
        var entry = new TaskColumnHistory(task(1, "Write the migration"), column(2, "Doing"));
        entry.setId(9);
        entry.setHistoryOrder(3);

        TaskColumnHistoryDto dto = new TaskColumnHistoryMapper().toDTO(entry);

        assertThat(dto.getId()).isEqualTo(9);
        assertThat(dto.getTaskId()).isEqualTo(1);
        assertThat(dto.getTaskTitle()).isEqualTo("Write the migration");
        assertThat(dto.getColumnId()).isEqualTo(2);
        assertThat(dto.getChangedAt()).isEqualTo(entry.getChangedAt());
    }

    @Test
    @DisplayName("the DTO reports the stored name, not the stage's current one")
    void theDtoReportsTheStoredName() {
        var stage = column(2, "Doing");
        var entry = new TaskColumnHistory(task(1, "Write the migration"), stage);
        stage.setName("In Progress");

        assertThat(new TaskColumnHistoryMapper().toDTO(entry).getColumnName()).isEqualTo("Doing");
    }

    @Test
    @DisplayName("two entries describing the same move are equal, which is what makes them comparable")
    void equalEntriesCompareEqual() {
        var moved = LocalDateTime.of(2026, 8, 31, 12, 0);
        var one = new TaskColumnHistoryDto(1, 2, "Write the migration", 3, "Doing", moved);
        var other = new TaskColumnHistoryDto(1, 2, "Write the migration", 3, "Doing", moved);

        // A Lombok @Data record of a value, so this is the generated contract rather than a
        // decision - but a client that de-duplicates a feed depends on it holding.
        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
        assertThat(one.toString()).contains("Doing");
    }
}
