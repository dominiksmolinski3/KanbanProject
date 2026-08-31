package pl.myproject.kanbanproject2.layout.column;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskService;
import pl.myproject.kanbanproject2.user.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Transactional
@Service
public class ColumnService {

    private final ColumnRepository columnRepository;
    private final ColumnMapper columnMapper;
    private final TaskService taskService;
    private final BoardService boardService;

    public List<ColumnDto> getAllColumns(User caller, Integer boardId) {
        var board = boardService.resolve(caller, boardId);
        return columnRepository.findByBoardOrderByPositionAsc(board).stream()
                .map(columnMapper).toList();
    }

    public ColumnResponseDto addNewColumn(User caller, Integer boardId, CreateColumnRequest request) {
        var board = boardService.resolve(caller, boardId);

        var column = new Column();
        column.setName(request.name());
        column.setWipLimit(request.wipLimit());
        column.setBoard(board);
        column.setPosition(request.position() != null
                ? request.position()
                : nextPosition(board));
        return columnMapper.toResponseDto(columnRepository.save(column));
    }

    /**
     * The next free position on this board, taken from the highest one in use rather than from a
     * row count. A count drops after any delete, so the next create hands out a position that is
     * still taken, and two concurrent creates read the same count. It is per board because a
     * position is what the board renders in order, and two boards number their stages
     * independently.
     */
    private int nextPosition(Board board) {
        return columnRepository.findMaxPosition(board).orElse(0) + 1;
    }

    public ColumnDto patchColumn(User caller, ColumnDto columnDto, Integer id) {
        var existingColumn = findColumn(caller, id);

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
    public void deleteColumn(User caller, Integer id) {
        var column = findColumn(caller, id);

        if (column.getTasks() != null) {
            for (Task task : List.copyOf(column.getTasks())) {
                taskService.deleteTask(caller, task.getId());
            }
            column.getTasks().clear();
        }

        columnRepository.delete(column);
    }

    public ColumnDto getColumnById(User caller, Integer id) {
        return columnMapper.apply(findColumn(caller, id));
    }

    public ColumnDto updateColumnPosition(User caller, Integer id, Integer position) {
        var column = findColumn(caller, id);
        column.setPosition(position);
        return columnMapper.apply(columnRepository.save(column));
    }

    /**
     * Renumbers a board's stages in one transaction, from the ids in the order they should read.
     *
     * <p>Dragging a stage used to send one PATCH per column and swallow each failure separately,
     * which since the {@code @Version} landed can leave the board half-reordered: the columns whose
     * write went through keep their new place and the rest keep their old one. One call is one
     * transaction, so either the whole order takes or none of it does.
     */
    public List<ColumnDto> reorderColumns(User caller, List<Integer> orderedIds) {
        if (orderedIds.size() != Set.copyOf(orderedIds).size()) {
            throw new GlobalException(ExceptionIdentifier.INVALID_REORDER,
                    "The same column appears more than once in the requested order");
        }

        var columns = orderedIds.stream().map(id -> findColumn(caller, id)).toList();
        columns.forEach(column -> boardService.requireSameBoard(columns.get(0).getBoard(), column.getBoard()));

        var reordered = new ArrayList<ColumnDto>(columns.size());
        for (int position = 0; position < columns.size(); position++) {
            var column = columns.get(position);
            column.setPosition(position);
            reordered.add(columnMapper.apply(columnRepository.save(column)));
        }
        return reordered;
    }

    /**
     * Looks the column up and refuses to hand it back unless the caller is on its board.
     *
     * <p>A column on somebody else's board answers as one that is not there at all: same status,
     * same body. Answering 403 would confirm the id is in use, which is enough to map out a board
     * the caller cannot open.
     */
    private Column findColumn(User caller, Integer id) {
        var column = columnRepository.findById(id).orElseThrow(() -> columnNotFound(id));
        if (!column.getBoard().isVisibleTo(caller)) {
            throw columnNotFound(id);
        }
        return column;
    }

    private GlobalException columnNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.COLUMN_NOT_FOUND,
                "Column not found with id: " + id);
    }
}
