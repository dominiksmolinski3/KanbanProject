package pl.myproject.kanbanproject2.layout.row;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;

import java.util.List;
import java.util.Objects;

@RequiredArgsConstructor
@Transactional
@Service
public class RowService {

    private final RowRepository rowRepository;
    private final RowMapper rowMapper;
    private final TaskRepository taskRepository;

    public List<RowDto> getAllRows() {
        return rowRepository.findAll().stream().map(rowMapper).toList();
    }

    public RowResponseDto createRow(CreateRowRequest request) {
        var row = new Row();
        row.setName(request.name());
        row.setWipLimit(request.wipLimit());
        row.setPosition(request.position() != null
                ? request.position()
                : nextPosition());
        return rowMapper.toResponseDto(rowRepository.save(row));
    }

    /**
     * The next free position, taken from the highest one in use rather than from a row count.
     * A count drops after any delete, so the next create hands out a position that is still
     * taken, and two concurrent creates read the same count.
     */
    private int nextPosition() {
        return rowRepository.findAll().stream()
                .map(Row::getPosition)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    public RowDto patchRow(RowDto rowDto, Integer id) {
        var existingRow = findRow(id);

        if (rowDto.name() != null) {
            existingRow.setName(rowDto.name());
        }
        if (rowDto.wipLimit() != null) {
            existingRow.setWipLimit(rowDto.wipLimit());
        }
        if (rowDto.position() != null) {
            existingRow.setPosition(rowDto.position());
        }
        return rowMapper.apply(rowRepository.save(existingRow));
    }

    /**
     * Takes every task out of the swimlane before removing it.
     *
     * <p>{@code Row.tasks} cascades PERSIST and MERGE only, so nothing in the mapping clears
     * {@code row_id} — the delete used to fail on the foreign key whenever the swimlane still held a
     * task. A task outlives its swimlane: the column is what puts it on the board.
     */
    public void deleteRow(Integer id) {
        var row = findRow(id);

        if (row.getTasks() != null) {
            for (Task task : List.copyOf(row.getTasks())) {
                task.setRow(null);
                taskRepository.save(task);
            }
            row.getTasks().clear();
        }

        rowRepository.delete(row);
    }

    public RowDto getRowById(Integer id) {
        return rowRepository.findById(id).map(rowMapper).orElseThrow(() -> rowNotFound(id));
    }

    public RowDto updateRowPosition(Integer id, Integer position) {
        var row = findRow(id);
        row.setPosition(position);
        return rowMapper.apply(rowRepository.save(row));
    }

    private Row findRow(Integer id) {
        return rowRepository.findById(id).orElseThrow(() -> rowNotFound(id));
    }

    private GlobalException rowNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.ROW_NOT_FOUND,
                "Row not found with id: " + id);
    }
}
