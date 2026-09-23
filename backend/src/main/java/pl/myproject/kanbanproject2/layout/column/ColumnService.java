package pl.myproject.kanbanproject2.layout.column;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskService;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
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
    private final BoardEventPublisher boardEvents;
    private final TaskColumnHistoryRepository taskColumnHistoryRepository;

    public List<ColumnDto> getAllColumns(User caller, Integer boardId) {
        var board = boardService.resolve(caller, boardId);
        return columnRepository.findByBoardOrderByPositionAsc(board).stream()
                .map(columnMapper).toList();
    }

    public ColumnResponseDto addNewColumn(User caller, Integer boardId, CreateColumnRequest request) {
        var board = boardService.resolve(caller, boardId);
        boardService.requireWritable(caller, board);

        var column = new Column();
        column.setName(request.name());
        column.setWipLimit(request.wipLimit());
        column.setBoard(board);
        column.setPosition(request.position() != null
                ? request.position()
                : nextPosition(board));
        var created = columnRepository.save(column);
        boardEvents.columnsChanged(created.getBoard());
        return columnMapper.toResponseDto(created);
    }

    /**
     * Saves a column, tells the board's other viewers, and maps it. See
     * {@code TaskService.saveAndAnnounce} - same reason, and the same build guard over it.
     */
    private ColumnDto saveAndAnnounce(Column column) {
        var saved = columnRepository.save(column);
        boardEvents.columnsChanged(saved.getBoard());
        return columnMapper.apply(saved);
    }

    /**
     * The next free position on this board, taken from the highest one in use rather than from a
     * row count, since a count drops after any delete and two concurrent creates would read the
     * same one.
     */
    private int nextPosition(Board board) {
        return columnRepository.findMaxPosition(board).orElse(0) + 1;
    }

    public ColumnDto patchColumn(User caller, ColumnDto columnDto, Integer id) {
        var existingColumn = findColumn(caller, id);
        boardService.requireWritable(caller, existingColumn.getBoard());

        if (columnDto.name() != null) {
            existingColumn.setName(columnDto.name());
        }
        if (columnDto.wipLimit() != null) {
            existingColumn.setWipLimit(columnDto.wipLimit());
        }
        if (columnDto.position() != null) {
            existingColumn.setPosition(columnDto.position());
        }
        return saveAndAnnounce(existingColumn);
    }

    /**
     * Removes the column and, with it, the tasks still in it. {@code Column.tasks} cascades ALL,
     * but the cascade alone fails on the foreign key from each task's {@code task_column_history}
     * rows (not nullable), so the deletion goes through {@link TaskService#deleteTask} instead,
     * which already unwinds history and parent/child links.
     *
     * <p>A task that passed through this column and later moved on leaves a
     * {@code task_column_history} row behind that the loop above never touches, hitting the same
     * foreign key. Those entries are detached rather than deleted, leaving the task and the rest of
     * its history unaffected — the same reasoning {@code TaskActivityRecorder.detachFrom} applies.
     */
    public void deleteColumn(User caller, Integer id) {
        var column = findColumn(caller, id);
        boardService.requireWritable(caller, column.getBoard());

        // Emptied before the loop, not after it. Column.tasks cascades ALL, and deleting each task
        // runs queries that flush first - so a task deleted in one iteration, still sitting in this
        // collection, was persisted straight back by the next iteration's flush, and the commit then
        // failed on a live task pointing at the deleted column. A column with two or more cards
        // could not be deleted at all: 500, every time, measured.
        if (column.getTasks() != null) {
            var tasks = List.copyOf(column.getTasks());
            column.getTasks().clear();
            for (Task task : tasks) {
                taskService.deleteTask(caller, task.getId());
            }
        }

        var strandedHistory = taskColumnHistoryRepository.findByColumn(column);
        strandedHistory.forEach(entry -> entry.setColumn(null));
        taskColumnHistoryRepository.saveAll(strandedHistory);

        // V23's ON DELETE SET NULL does the same in the database; clearing it here as well stops a
        // board already loaded in this transaction from flushing the deleted id back over it.
        column.getBoard().forgetFlowColumn(column);

        columnRepository.delete(column);
        boardEvents.columnsChanged(column.getBoard());
    }

    public ColumnDto getColumnById(User caller, Integer id) {
        return columnMapper.apply(findColumn(caller, id));
    }

    public ColumnDto updateColumnPosition(User caller, Integer id, Integer position) {
        var column = findColumn(caller, id);
        boardService.requireWritable(caller, column.getBoard());
        column.setPosition(position);
        return saveAndAnnounce(column);
    }

    /**
     * Renumbers a board's stages in one transaction, from the ids in the order they should read.
     * One PATCH per column, the previous shape, could leave the board half-reordered once
     * {@code @Version} made one write in the batch fail and the rest succeed.
     */
    public List<ColumnDto> reorderColumns(User caller, List<Integer> orderedIds) {
        if (orderedIds.size() != Set.copyOf(orderedIds).size()) {
            throw new GlobalException(ExceptionIdentifier.INVALID_REORDER,
                    "The same column appears more than once in the requested order");
        }

        var columns = orderedIds.stream().map(id -> findColumn(caller, id)).toList();
        boardService.requireWritable(caller, columns.get(0).getBoard());
        columns.forEach(column -> boardService.requireSameBoard(columns.get(0).getBoard(), column.getBoard()));

        var reordered = new ArrayList<ColumnDto>(columns.size());
        for (int position = 0; position < columns.size(); position++) {
            var column = columns.get(position);
            column.setPosition(position);
            reordered.add(saveAndAnnounce(column));
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
