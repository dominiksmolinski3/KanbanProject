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

    private ColumnDto saveAndAnnounce(Column column) {
        var saved = columnRepository.save(column);
        boardEvents.columnsChanged(saved.getBoard());
        return columnMapper.apply(saved);
    }

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

    public void deleteColumn(User caller, Integer id) {
        var column = findColumn(caller, id);
        boardService.requireWritable(caller, column.getBoard());

        // Clear before the loop: Column.tasks cascades ALL, so a mid-loop flush would re-persist tasks already deleted.
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
