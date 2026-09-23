package pl.myproject.kanbanproject2.task.flow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A board's flow over a window of days: the cumulative flow diagram, the cycle times of the cards
 * that finished in the window, and how many finished each day. Everything is derived from
 * {@code task_column_history}; nothing here is stored.
 *
 * <p>{@code cumulativeFlow} carries one count per column per day, in the same order as
 * {@code columns}, rather than a map keyed by id - a chart draws bands in column order, and a map
 * would make every client re-derive the order the server already knows.
 *
 * <p>{@code startColumnId}/{@code doneColumnId} are what this answer was computed with;
 * {@code definedStartColumnId}/{@code definedDoneColumnId} are the board's own stored definition
 * (FLOW-02), null where the board has none - so the screen can tell "the board says" from "you
 * picked" and offer the owner a save only when the two differ.
 */
public record FlowMetricsDto(Integer boardId,
                             LocalDate from,
                             LocalDate to,
                             Integer startColumnId,
                             Integer doneColumnId,
                             Integer definedStartColumnId,
                             Integer definedDoneColumnId,
                             List<Column> columns,
                             List<CumulativeFlowDay> cumulativeFlow,
                             CycleTimeSummary cycleTime,
                             List<CycleTimeSample> samples,
                             List<ThroughputDay> throughput) {

    /** A column as it is now; positions decide what "at or past" a column means. */
    public record Column(Integer id, String name, Integer position) {
    }

    /** How many cards sat in each column at the end of {@code date}, aligned with {@code columns}. */
    public record CumulativeFlowDay(LocalDate date, List<Integer> counts) {
    }

    /**
     * Hours from start to done over the window's finished cards. The statistics are null when
     * nothing finished, rather than zero - "no card took any time" is not what an empty window says.
     */
    public record CycleTimeSummary(int count, Double averageHours, Double medianHours, Double p85Hours) {
    }

    /** One finished card, so the screen can plot each one and name it on hover. */
    public record CycleTimeSample(Integer taskId, String title, LocalDateTime doneAt, double hours) {
    }

    public record ThroughputDay(LocalDate date, int count) {
    }
}
