package pl.myproject.kanbanproject2.layout.row;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

@RequiredArgsConstructor
@Transactional
@Service
public class RowService {

    private final RowRepository rowRepository;
    private final RowMapper rowMapper;
    private final TaskRepository taskRepository;
    private final BoardService boardService;

    public List<RowDto> getAllRows(User caller, Integer boardId) {
        var board = boardService.resolve(caller, boardId);
        return rowRepository.findByBoardOrderByPositionAsc(board).stream().map(rowMapper).toList();
    }

    public RowResponseDto createRow(User caller, Integer boardId, CreateRowRequest request) {
        var board = boardService.resolve(caller, boardId);

        var row = new Row();
        row.setName(request.name());
        row.setWipLimit(request.wipLimit());
        row.setBoard(board);
        row.setPosition(request.position() != null
                ? request.position()
                : nextPosition(board));
        return rowMapper.toResponseDto(rowRepository.save(row));
    }

    /**
     * The next free position on this board, taken from the highest one in use rather than from a
     * row count. A count drops after any delete, so the next create hands out a position that is
     * still taken, and two concurrent creates read the same count.
     */
    private int nextPosition(Board board) {
        return rowRepository.findMaxPosition(board).orElse(0) + 1;
    }

    public RowDto patchRow(User caller, RowDto rowDto, Integer id) {
        var existingRow = findRow(caller, id);

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
    public void deleteRow(User caller, Integer id) {
        var row = findRow(caller, id);

        if (row.getTasks() != null) {
            for (Task task : List.copyOf(row.getTasks())) {
                task.setRow(null);
                taskRepository.save(task);
            }
            row.getTasks().clear();
        }

        rowRepository.delete(row);
    }

    public RowDto getRowById(User caller, Integer id) {
        return rowMapper.apply(findRow(caller, id));
    }

    public RowDto updateRowPosition(User caller, Integer id, Integer position) {
        var row = findRow(caller, id);
        row.setPosition(position);
        return rowMapper.apply(rowRepository.save(row));
    }

    /** A swimlane on somebody else's board answers as one that is not there. See ColumnService. */
    private Row findRow(User caller, Integer id) {
        var row = rowRepository.findById(id).orElseThrow(() -> rowNotFound(id));
        if (!row.getBoard().isVisibleTo(caller)) {
            throw rowNotFound(id);
        }
        return row;
    }

    private GlobalException rowNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.ROW_NOT_FOUND,
                "Row not found with id: " + id);
    }
}
