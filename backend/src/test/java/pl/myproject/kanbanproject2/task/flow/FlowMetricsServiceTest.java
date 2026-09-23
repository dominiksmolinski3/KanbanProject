package pl.myproject.kanbanproject2.task.flow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistory;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowMetricsServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 9, 8);
    private static final LocalDate TO = LocalDate.of(2026, 9, 10);

    private TaskColumnHistoryRepository historyRepository;
    private ColumnRepository columnRepository;
    private FlowMetricsService service;
    private User caller;
    private Board board;
    private pl.myproject.kanbanproject2.board.BoardService boardService;
    private Column a;
    private Column b;
    private Column c;
    private final List<TaskColumnHistory> history = new ArrayList<>();

    @BeforeEach
    void setUp() {
        historyRepository = mock(TaskColumnHistoryRepository.class);
        columnRepository = mock(ColumnRepository.class);
        var tenant = TenancyFixtures.tenant();
        caller = tenant.caller();
        board = tenant.board();
        boardService = tenant.boardService();
        when(boardService.requireOwned(caller, board.getId())).thenReturn(board);
        service = new FlowMetricsService(historyRepository, columnRepository, boardService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        a = column(10, "A", 1);
        b = column(11, "B", 2);
        c = column(12, "C", 3);
        when(columnRepository.findByBoardOrderByPositionAsc(board)).thenReturn(List.of(a, b, c));
        when(historyRepository.findBoardHistoryBefore(eq(board), any())).thenReturn(history);

        var card1 = task(1, c);
        arrive(card1, a, "2026-09-07T09:00");
        arrive(card1, b, "2026-09-08T10:00");
        arrive(card1, c, "2026-09-09T10:00");
        var card2 = task(2, b);
        arrive(card2, a, "2026-09-08T08:00");
        arrive(card2, b, "2026-09-10T08:00");
        var card3 = task(3, null);
        arrive(card3, a, "2026-09-07T00:00");
        var card4 = task(4, c);
        arrive(card4, a, "2026-09-01T00:00");
        arrive(card4, c, "2026-09-05T00:00");
    }

    private Column column(int id, String name, int position) {
        var column = new Column();
        column.setId(id);
        column.setName(name);
        column.setPosition(position);
        column.setBoard(board);
        return column;
    }

    private Task task(int id, Column current) {
        var task = new Task();
        task.setId(id);
        task.setTitle("Card " + id);
        task.setBoard(board);
        task.setColumn(current);
        return task;
    }

    private void arrive(Task task, Column column, String at) {
        var row = new TaskColumnHistory();
        row.setTask(task);
        row.setColumn(column);
        row.setColumnName(column == null ? null : column.getName());
        row.setChangedAt(LocalDateTime.parse(at));
        row.setHistoryOrder(0);
        history.add(row);
    }

    private FlowMetricsDto metrics(Integer start, Integer done) {
        return service.metrics(caller, null, FROM, TO, start, done);
    }

    @Nested
    @DisplayName("the cumulative flow diagram")
    class CumulativeFlow {

        @Test
        @DisplayName("counts each card in the column it was in at the end of each day")
        void countsAtTheEndOfEachDay() {
            var flow = metrics(null, null).cumulativeFlow();

            assertThat(flow).extracting(FlowMetricsDto.CumulativeFlowDay::date)
                    .containsExactly(FROM, FROM.plusDays(1), TO);
            assertThat(flow.get(0).counts()).containsExactly(1, 1, 1);
            assertThat(flow.get(1).counts()).containsExactly(1, 0, 2);
            assertThat(flow.get(2).counts()).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("a card taken off the board is in no column once its last interval is open")
        void offBoardCardCountsNowhere() {
            var total = metrics(null, null).cumulativeFlow().stream()
                    .mapToInt(day -> day.counts().stream().mapToInt(Integer::intValue).sum())
                    .max().orElseThrow();

            assertThat(total).isEqualTo(3);
        }

        @Test
        @DisplayName("names the columns in board order, so each count lines up with its band")
        void columnsInOrder() {
            assertThat(metrics(null, null).columns()).extracting(FlowMetricsDto.Column::name)
                    .containsExactly("A", "B", "C");
        }
    }

    @Nested
    @DisplayName("cycle time and throughput")
    class CycleTime {

        @Test
        @DisplayName("by default runs from arrival on the board to the last column, for cards done in the window")
        void defaultIsLeadTime() {
            var result = metrics(null, null);

            assertThat(result.doneColumnId()).isEqualTo(12);
            assertThat(result.startColumnId()).isNull();
            assertThat(result.samples()).extracting(FlowMetricsDto.CycleTimeSample::taskId).containsExactly(1);
            assertThat(result.samples().getFirst().hours()).isEqualTo(49.0);
            assertThat(result.cycleTime()).isEqualTo(new FlowMetricsDto.CycleTimeSummary(1, 49.0, 49.0, 49.0));
        }

        @Test
        @DisplayName("starts the clock at the chosen column")
        void startColumnMovesTheClock() {
            var result = metrics(b.getId(), null);

            assertThat(result.samples().getFirst().hours()).isEqualTo(24.0);
        }

        @Test
        @DisplayName("counts throughput on the day each card finished")
        void throughputPerDay() {
            assertThat(metrics(null, null).throughput()).extracting(FlowMetricsDto.ThroughputDay::count)
                    .containsExactly(0, 1, 0);
        }

        @Test
        @DisplayName("an earlier done column finishes more cards: arriving at or past it counts")
        void atOrPastCounts() {
            var result = metrics(null, b.getId());

            assertThat(result.samples()).extracting(FlowMetricsDto.CycleTimeSample::taskId).containsExactly(1, 2);
            assertThat(result.throughput()).extracting(FlowMetricsDto.ThroughputDay::count)
                    .containsExactly(1, 0, 1);
        }

        @Test
        @DisplayName("a card finished, reopened and finished again counts once, at its first finish")
        void firstFinishCounts() {
            history.clear();
            var bounced = task(5, c);
            arrive(bounced, a, "2026-09-08T00:00");
            arrive(bounced, c, "2026-09-08T06:00");
            arrive(bounced, b, "2026-09-08T07:00");
            arrive(bounced, c, "2026-09-09T12:00");

            var result = metrics(null, null);

            assertThat(result.samples()).hasSize(1);
            assertThat(result.samples().getFirst().hours()).isEqualTo(6.0);
        }

        @Test
        @DisplayName("percentiles are nearest-rank: every number reported is one a card actually took")
        void nearestRank() {
            var samples = new ArrayList<FlowMetricsDto.CycleTimeSample>();
            for (int i = 1; i <= 10; i++) {
                samples.add(new FlowMetricsDto.CycleTimeSample(i, "t", LocalDateTime.MIN, i * 10.0));
            }

            var summary = FlowMetricsService.summarise(samples);

            assertThat(summary.medianHours()).isEqualTo(50.0);
            assertThat(summary.p85Hours()).isEqualTo(90.0);
            assertThat(summary.averageHours()).isEqualTo(55.0);
        }

        @Test
        @DisplayName("an empty window reports no statistics rather than zeroes")
        void emptyWindow() {
            history.clear();

            assertThat(metrics(null, null).cycleTime())
                    .isEqualTo(new FlowMetricsDto.CycleTimeSummary(0, null, null, null));
        }

        @Test
        @DisplayName("a deleted column's rows mark boundaries but finish nothing and draw no band")
        void deletedColumnRows() {
            history.clear();
            var card = task(6, a);
            arrive(card, null, "2026-09-08T00:00");
            arrive(card, a, "2026-09-09T00:00");

            var result = metrics(null, null);

            assertThat(result.cumulativeFlow().get(0).counts()).containsExactly(0, 0, 0);
            assertThat(result.cumulativeFlow().get(1).counts()).containsExactly(1, 0, 0);
            assertThat(result.samples()).isEmpty();
        }
    }

    @Nested
    @DisplayName("what it refuses")
    class Refusals {

        private void assertRefused(Runnable call) {
            assertThatThrownBy(call::run)
                    .isInstanceOfSatisfying(GlobalException.class, e ->
                            assertThat(e.getIdentifier()).isEqualTo(ExceptionIdentifier.INVALID_FLOW_REQUEST));
        }

        @Test
        @DisplayName("a window of more than 180 days, rather than clamping it")
        void tooLong() {
            assertRefused(() -> service.metrics(caller, null, TO.minusDays(FlowMetricsService.MAX_DAYS), TO, null, null));
            verify(historyRepository, never()).findBoardHistoryBefore(any(), any());
        }

        @Test
        @DisplayName("exactly 180 days is fine")
        void exactlyTheLimit() {
            var result = service.metrics(caller, null, TO.minusDays(FlowMetricsService.MAX_DAYS - 1L), TO, null, null);

            assertThat(result.cumulativeFlow()).hasSize(FlowMetricsService.MAX_DAYS);
        }

        @Test
        @DisplayName("a window that runs backwards")
        void backwards() {
            assertRefused(() -> service.metrics(caller, null, TO, FROM, null, null));
        }

        @Test
        @DisplayName("a column that is not on this board")
        void foreignColumn() {
            assertRefused(() -> metrics(999, null));
            assertRefused(() -> metrics(null, 999));
        }

        @Test
        @DisplayName("a start column after the done column")
        void startAfterDone() {
            assertRefused(() -> metrics(c.getId(), a.getId()));
        }
    }

    @Test
    @DisplayName("defaults to the last thirty days ending today")
    void defaultWindow() {
        var result = service.metrics(caller, null, null, null, null, null);

        assertThat(result.to()).isEqualTo(TO);
        assertThat(result.from()).isEqualTo(TO.minusDays(FlowMetricsService.DEFAULT_DAYS - 1L));
        assertThat(result.cumulativeFlow()).hasSize(FlowMetricsService.DEFAULT_DAYS);
    }

    @Test
    @DisplayName("a board with no columns answers an empty panel rather than failing")
    void noColumns() {
        when(columnRepository.findByBoardOrderByPositionAsc(board)).thenReturn(List.of());

        var result = metrics(null, null);

        assertThat(result.columns()).isEmpty();
        assertThat(result.throughput()).hasSize(3);
        assertThat(result.cycleTime().count()).isZero();
    }

    @Nested
    @DisplayName("the board's own definition")
    class Definition {

        @Test
        @DisplayName("is what a read with no choice of its own measures to")
        void storedDoneIsTheDefault() {
            board.setFlowDoneColumn(b);

            var flow = metrics(null, null);

            assertThat(flow.doneColumnId()).isEqualTo(b.getId());
            assertThat(flow.definedDoneColumnId()).isEqualTo(b.getId());
            assertThat(flow.cycleTime().count()).isEqualTo(2);
        }

        @Test
        @DisplayName("gives way to a choice made in the request, and still says what it is")
        void requestWins() {
            board.setFlowDoneColumn(b);

            var flow = metrics(null, c.getId());

            assertThat(flow.doneColumnId()).isEqualTo(c.getId());
            assertThat(flow.definedDoneColumnId()).isEqualTo(b.getId());
            assertThat(flow.cycleTime().count()).isEqualTo(1);
        }

        @Test
        @DisplayName("an unset board reads exactly as FEAT-07 did")
        void unsetIsTheOldRule() {
            var flow = metrics(null, null);

            assertThat(flow.doneColumnId()).isEqualTo(c.getId());
            assertThat(flow.startColumnId()).isNull();
            assertThat(flow.definedStartColumnId()).isNull();
            assertThat(flow.definedDoneColumnId()).isNull();
        }

        @Test
        @DisplayName("a stored start that no longer fits gives way rather than refusing the screen")
        void storedStartGivesWay() {
            board.setFlowStartColumn(c);
            board.setFlowDoneColumn(b);

            assertThat(metrics(null, null).startColumnId()).isNull();
            assertThat(metrics(null, a.getId()).startColumnId()).isNull();
        }

        @Test
        @DisplayName("a stored done that no longer fits a chosen start gives way to the last column")
        void storedDoneGivesWay() {
            board.setFlowDoneColumn(a);

            var flow = metrics(b.getId(), null);

            assertThat(flow.startColumnId()).isEqualTo(b.getId());
            assertThat(flow.doneColumnId()).isEqualTo(c.getId());
        }

        @Test
        @DisplayName("two explicit choices that contradict each other are still a 400")
        void explicitContradictionRefused() {
            board.setFlowDoneColumn(c);

            assertThatThrownBy(() -> metrics(c.getId(), a.getId()))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_FLOW_REQUEST);
        }

        @Test
        @DisplayName("a stored column that is no longer the board's is ignored")
        void foreignStoredColumnIgnored() {
            var elsewhere = column(99, "Elsewhere", 4);
            board.setFlowDoneColumn(elsewhere);

            var flow = metrics(null, null);

            assertThat(flow.doneColumnId()).isEqualTo(c.getId());
            assertThat(flow.definedDoneColumnId()).isNull();
        }

        @Test
        @DisplayName("the owner stores it, and nulls put either end back on the default")
        void ownerDefines() {
            var saved = service.define(caller, null, new FlowDefinitionRequest(a.getId(), b.getId()));

            assertThat(saved).isEqualTo(new FlowDefinitionDto(board.getId(), a.getId(), b.getId()));
            assertThat(board.getFlowStartColumn()).isSameAs(a);
            assertThat(board.getFlowDoneColumn()).isSameAs(b);

            service.define(caller, null, new FlowDefinitionRequest(null, null));
            assertThat(board.getFlowStartColumn()).isNull();
            assertThat(board.getFlowDoneColumn()).isNull();
        }

        @Test
        @DisplayName("refuses a column from another board, and a start after done")
        void refusesNonsense() {
            assertThatThrownBy(() -> service.define(caller, null, new FlowDefinitionRequest(null, 404)))
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_FLOW_REQUEST);
            assertThatThrownBy(() -> service.define(caller, null, new FlowDefinitionRequest(c.getId(), a.getId())))
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_FLOW_REQUEST);
            service.define(caller, null, new FlowDefinitionRequest(c.getId(), null));
            assertThat(board.getFlowStartColumn()).isSameAs(c);
        }

        @Test
        @DisplayName("is the owner's alone to set, and a refusal changes nothing")
        void onlyTheOwner() {
            doThrow(new GlobalException(ExceptionIdentifier.NOT_BOARD_OWNER))
                    .when(boardService).requireOwned(caller, board.getId());

            assertThatThrownBy(() -> service.define(caller, null, new FlowDefinitionRequest(null, b.getId())))
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.NOT_BOARD_OWNER);
            assertThat(board.getFlowDoneColumn()).isNull();
        }
    }
}
