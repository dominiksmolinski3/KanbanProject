package pl.myproject.kanbanproject2.task.flow;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistory;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The standard Kanban instrument panel, read out of rows this application has been writing all
 * along (FEAT-07). {@code task_column_history} is an interval series - one row per arrival in a
 * column - kept apart from the activity feed because it answers a different question, and until
 * now it answered it for one card at a time in the task panel. Aggregated per board, the same rows
 * are a cumulative flow diagram, a cycle-time distribution and a throughput count. No new table
 * and no new write path.
 *
 * <p>Four decisions are worth knowing before changing any number this produces:
 * <ul>
 *   <li><b>A window, not paging.</b> The answer is an aggregate, so there is nothing to page; what
 *       grows is the span of days. It defaults to the last {@link #DEFAULT_DAYS} and refuses more
 *       than {@link #MAX_DAYS} with {@code 400 INVALID_FLOW_REQUEST} rather than a silent clamp -
 *       the same refusal the search, the feed and chat make, for the same reason.</li>
 *   <li><b>"Done" is a column, and "at or past it" counts.</b> A card that skips from In Progress
 *       straight to Closed has finished, so arriving in any column positioned at or after the done
 *       column is the finish; the same rule marks the start. Positions are today's, because a
 *       history row points at a column and a column has only its current position. The defaults
 *       are the board's last column for done and arrival on the board for start, which makes the
 *       default cycle time a lead time; the screen lets people choose.</li>
 *   <li><b>The first finish is the finish.</b> A card reopened and finished again counts once, at
 *       its first arrival - otherwise one card bounced between Review and Done would read as
 *       throughput.</li>
 *   <li><b>What the history cannot say, this does not guess.</b> A deleted card takes its history
 *       with it, so it is in no count. A card taken off the board (no column) has an open last
 *       interval with no recorded end, so from that point it is in no column. A deleted column's
 *       rows keep their place as interval boundaries but draw no band.</li>
 * </ul>
 *
 * <p>Days are the server's calendar days, the same zone the history rows are written in by
 * {@code LocalDateTime.now()} - so the clock here is the system default zone, not UTC, or a card
 * moved just before midnight would land on the wrong day.
 */
@Transactional
@Service
public class FlowMetricsService {

    public static final int DEFAULT_DAYS = 30;
    public static final int MAX_DAYS = 180;

    private final TaskColumnHistoryRepository historyRepository;
    private final ColumnRepository columnRepository;
    private final BoardService boardService;
    private final Clock clock;

    @Autowired
    public FlowMetricsService(TaskColumnHistoryRepository historyRepository,
                              ColumnRepository columnRepository,
                              BoardService boardService) {
        this(historyRepository, columnRepository, boardService, Clock.systemDefaultZone());
    }

    FlowMetricsService(TaskColumnHistoryRepository historyRepository,
                       ColumnRepository columnRepository,
                       BoardService boardService,
                       Clock clock) {
        this.historyRepository = historyRepository;
        this.columnRepository = columnRepository;
        this.boardService = boardService;
        this.clock = clock;
    }

    public FlowMetricsDto metrics(User caller, Integer boardId, LocalDate from, LocalDate to,
                                  Integer startColumnId, Integer doneColumnId) {
        var now = LocalDateTime.now(clock);
        var lastDay = to != null ? to : now.toLocalDate();
        var firstDay = from != null ? from : lastDay.minusDays(DEFAULT_DAYS - 1L);
        long days = ChronoUnit.DAYS.between(firstDay, lastDay) + 1;
        if (days < 1 || days > MAX_DAYS) {
            throw invalid("the window must run forwards and cover at most " + MAX_DAYS + " days");
        }

        var board = boardService.resolve(caller, boardId);
        var columns = columnRepository.findByBoardOrderByPositionAsc(board);
        if (columns.isEmpty()) {
            return empty(board.getId(), firstDay, lastDay, days);
        }
        var done = doneColumnId == null ? columns.getLast() : columnOn(columns, doneColumnId);
        var start = startColumnId == null ? null : columnOn(columns, startColumnId);
        if (start != null && position(start) > position(done)) {
            throw invalid("work cannot start in a column after the one it is done in");
        }

        var windowEnd = lastDay.plusDays(1).atStartOfDay();
        var histories = byTask(historyRepository.findBoardHistoryBefore(board, windowEnd));

        var index = new HashMap<Integer, Integer>();
        for (int i = 0; i < columns.size(); i++) {
            index.put(columns.get(i).getId(), i);
        }

        var cumulativeFlow = new ArrayList<FlowMetricsDto.CumulativeFlowDay>();
        for (var day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            var endOfDay = day.plusDays(1).atStartOfDay();
            var at = endOfDay.isAfter(now) ? now : endOfDay;
            var counts = new int[columns.size()];
            for (var entry : histories.entrySet()) {
                var column = columnAt(entry.getKey(), entry.getValue(), at);
                if (column != null && index.containsKey(column.getId())) {
                    counts[index.get(column.getId())]++;
                }
            }
            cumulativeFlow.add(new FlowMetricsDto.CumulativeFlowDay(day,
                    java.util.Arrays.stream(counts).boxed().toList()));
        }

        var windowStart = firstDay.atStartOfDay();
        var samples = new ArrayList<FlowMetricsDto.CycleTimeSample>();
        var throughput = new LinkedHashMap<LocalDate, Integer>();
        for (var day = firstDay; !day.isAfter(lastDay); day = day.plusDays(1)) {
            throughput.put(day, 0);
        }
        for (var entry : histories.entrySet()) {
            var rows = entry.getValue();
            var doneAt = firstArrivalAtOrPast(rows, position(done));
            if (doneAt == null || doneAt.isBefore(windowStart) || !doneAt.isBefore(windowEnd)) {
                continue;
            }
            var startedAt = start == null ? rows.getFirst().getChangedAt() : firstArrivalAtOrPast(rows, position(start));
            if (startedAt == null || startedAt.isAfter(doneAt)) {
                continue;
            }
            var task = entry.getKey();
            samples.add(new FlowMetricsDto.CycleTimeSample(task.getId(), task.getTitle(), doneAt,
                    hours(Duration.between(startedAt, doneAt))));
            throughput.merge(doneAt.toLocalDate(), 1, Integer::sum);
        }
        samples.sort(Comparator.comparing(FlowMetricsDto.CycleTimeSample::doneAt)
                .thenComparing(FlowMetricsDto.CycleTimeSample::taskId));

        return new FlowMetricsDto(
                board.getId(), firstDay, lastDay,
                start == null ? null : start.getId(), done.getId(),
                columns.stream().map(c -> new FlowMetricsDto.Column(c.getId(), c.getName(), c.getPosition())).toList(),
                cumulativeFlow,
                summarise(samples),
                samples,
                throughput.entrySet().stream()
                        .map(e -> new FlowMetricsDto.ThroughputDay(e.getKey(), e.getValue())).toList());
    }

    // ------------------------------------------------------------------ intervals ---

    /** Each task's arrivals in order; the repository sorts, so this only has to group. */
    private static Map<Task, List<TaskColumnHistory>> byTask(List<TaskColumnHistory> rows) {
        var grouped = new LinkedHashMap<Integer, List<TaskColumnHistory>>();
        var tasks = new HashMap<Integer, Task>();
        for (var row : rows) {
            var task = row.getTask();
            tasks.putIfAbsent(task.getId(), task);
            grouped.computeIfAbsent(task.getId(), id -> new ArrayList<>()).add(row);
        }
        var result = new LinkedHashMap<Task, List<TaskColumnHistory>>();
        grouped.forEach((id, list) -> result.put(tasks.get(id), list));
        return result;
    }

    /**
     * The column a task was in at {@code at}: the last arrival strictly before it, since {@code at}
     * is the midnight that begins the next day and a move at 00:00 belongs to that day. The last interval
     * is open only while the task is still in that column - a task since taken off the board has an
     * end nobody recorded, and counting it forever would inflate every later day.
     */
    private static Column columnAt(Task task, List<TaskColumnHistory> rows, LocalDateTime at) {
        TaskColumnHistory current = null;
        int i = 0;
        for (; i < rows.size(); i++) {
            if (!rows.get(i).getChangedAt().isBefore(at)) {
                break;
            }
            current = rows.get(i);
        }
        if (current == null) {
            return null;
        }
        boolean open = i == rows.size();
        if (open && !sameColumn(task.getColumn(), current.getColumn())) {
            return null;
        }
        return current.getColumn();
    }

    private static LocalDateTime firstArrivalAtOrPast(List<TaskColumnHistory> rows, int threshold) {
        for (var row : rows) {
            var column = row.getColumn();
            if (column != null && position(column) >= threshold) {
                return row.getChangedAt();
            }
        }
        return null;
    }

    private static boolean sameColumn(Column a, Column b) {
        return a != null && b != null && a.getId() != null && a.getId().equals(b.getId());
    }

    private static int position(Column column) {
        return column.getPosition() == null ? Integer.MAX_VALUE : column.getPosition();
    }

    // ------------------------------------------------------------------ statistics ---

    /** Nearest-rank percentiles: every reported number is a cycle time some card actually had. */
    static FlowMetricsDto.CycleTimeSummary summarise(List<FlowMetricsDto.CycleTimeSample> samples) {
        if (samples.isEmpty()) {
            return new FlowMetricsDto.CycleTimeSummary(0, null, null, null);
        }
        var sorted = samples.stream().mapToDouble(FlowMetricsDto.CycleTimeSample::hours).sorted().toArray();
        double average = java.util.Arrays.stream(sorted).average().orElse(0);
        return new FlowMetricsDto.CycleTimeSummary(sorted.length,
                round(average), percentile(sorted, 0.5), percentile(sorted, 0.85));
    }

    private static double percentile(double[] sorted, double p) {
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private static double hours(Duration duration) {
        return round(duration.toSeconds() / 3600.0);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    // ------------------------------------------------------------------ edges ---

    /**
     * A column id that is not on this board is a 400, the same answer whether it exists elsewhere
     * or not at all - it names nothing the caller could not already see, so it discloses nothing.
     */
    private static Column columnOn(List<Column> columns, Integer id) {
        return columns.stream()
                .filter(column -> id.equals(column.getId()))
                .findFirst()
                .orElseThrow(() -> invalid("column " + id + " is not on this board"));
    }

    private static FlowMetricsDto empty(Integer boardId, LocalDate from, LocalDate to, long days) {
        var cumulative = new ArrayList<FlowMetricsDto.CumulativeFlowDay>();
        var throughput = new ArrayList<FlowMetricsDto.ThroughputDay>();
        for (int i = 0; i < days; i++) {
            cumulative.add(new FlowMetricsDto.CumulativeFlowDay(from.plusDays(i), List.of()));
            throughput.add(new FlowMetricsDto.ThroughputDay(from.plusDays(i), 0));
        }
        return new FlowMetricsDto(boardId, from, to, null, null, List.of(), cumulative,
                summarise(List.of()), List.of(), throughput);
    }

    private static GlobalException invalid(String message) {
        return new GlobalException(ExceptionIdentifier.INVALID_FLOW_REQUEST, message);
    }
}
