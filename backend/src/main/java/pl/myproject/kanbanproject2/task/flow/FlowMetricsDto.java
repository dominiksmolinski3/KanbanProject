package pl.myproject.kanbanproject2.task.flow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    public record Column(Integer id, String name, Integer position) {
    }

    public record CumulativeFlowDay(LocalDate date, List<Integer> counts) {
    }

    public record CycleTimeSummary(int count, Double averageHours, Double medianHours, Double p85Hours) {
    }

    public record CycleTimeSample(Integer taskId, String title, LocalDateTime doneAt, double hours) {
    }

    public record ThroughputDay(LocalDate date, int count) {
    }
}
