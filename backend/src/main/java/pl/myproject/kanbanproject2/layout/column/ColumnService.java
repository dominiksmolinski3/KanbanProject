package pl.myproject.kanbanproject2.layout.column;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskService;

import java.util.List;
import java.util.Objects;

@RequiredArgsConstructor
@Transactional
@Service
public class ColumnService {

    private final ColumnRepository columnRepository;
    private final ColumnMapper columnMapper;
    private final TaskService taskService;

    public List<ColumnDto> getAllColumns() {
        return columnRepository.findAll().stream().map(columnMapper).toList();
    }

    public ColumnResponseDto addNewColumn(CreateColumnRequest request) {
        var column = new Column();
        column.setName(request.name());
        column.setWipLimit(request.wipLimit());
        column.setPosition(request.position() != null
                ? request.position()
                : nextPosition());
        return columnMapper.toResponseDto(columnRepository.save(column));
    }

    /**
     * The next free position, taken from the highest one in use rather than from a row count.
     * A count drops after any delete, so the next create hands out a position that is still
     * taken, and two concurrent creates read the same count.
     */
    private int nextPosition() {
        return columnRepository.findAll().stream()
                .map(Column::getPosition)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    public ColumnDto patchColumn(ColumnDto columnDto, Integer id) {
        var existingColumn = findColumn(id);

        if (columnDto.name() != null) {
            existingColumn.setName(columnDto.name());
        }
        if (columnDto.wipLimit() != null) {
            existingColumn.setWipLimit(columnDto.wipLimit());
        }
        if (columnDto.position() != null) {
            existingColumn.setPosition(columnDto.position());
        }
        return columnMapper.apply(columnRepository.save(existingColumn));
    }

    /**
     * Removes the column and, with it, the tasks that were still in it.
     *
     * <p>That is what {@code Column.tasks} has always declared with {@code cascade = ALL} — but the
     * cascade alone could not do it, because each of those tasks owns {@code task_column_history}
     * rows whose {@code task_id} is {@code nullable = false}. Deleting a column that still held a
     * task failed on that foreign key. {@link TaskService#deleteTask} already unwinds a task
     * properly — history, then parent and child links — so the deletion goes through it, and the
     * cascade is left with nothing to do.
     */
    public void deleteColumn(Integer id) {
        var column = findColumn(id);

        if (column.getTasks() != null) {
            for (Task task : List.copyOf(column.getTasks())) {
                taskService.deleteTask(task.getId());
            }
            column.getTasks().clear();
        }

        columnRepository.delete(column);
    }

    public ColumnDto getColumnById(Integer id) {
        return columnRepository.findById(id).map(columnMapper)
                .orElseThrow(() -> columnNotFound(id));
    }

    public ColumnDto updateColumnPosition(Integer id, Integer position) {
        var column = findColumn(id);
        column.setPosition(position);
        return columnMapper.apply(columnRepository.save(column));
    }

    private Column findColumn(Integer id) {
        return columnRepository.findById(id).orElseThrow(() -> columnNotFound(id));
    }

    private GlobalException columnNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.COLUMN_NOT_FOUND,
                "Column not found with id: " + id);
    }
}
