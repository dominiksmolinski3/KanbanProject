package pl.myproject.kanbanproject2.layout.row;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.user.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
@Transactional
@Service
public class RowService {

    private final RowRepository rowRepository;
    private final RowMapper rowMapper;
    private final TaskRepository taskRepository;
    private final BoardService boardService;
    private final BoardEventPublisher boardEvents;

    public List<RowDto> getAllRows(User caller, Integer boardId) {
        var board = boardService.resolve(caller, boardId);
        return rowRepository.findByBoardOrderByPositionAsc(board).stream().map(rowMapper).toList();
    }

    public RowResponseDto createRow(User caller, Integer boardId, CreateRowRequest request) {
        var board = boardService.resolve(caller, boardId);
        boardService.requireWritable(caller, board);

        var row = new Row();
        row.setName(request.name());
        row.setWipLimit(request.wipLimit());
        row.setBoard(board);
        row.setPosition(request.position() != null
                ? request.position()
                : nextPosition(board));
        var created = rowRepository.save(row);
        boardEvents.rowsChanged(created.getBoard());
        return rowMapper.toResponseDto(created);
    }

    private RowDto saveAndAnnounce(Row row) {
        var saved = rowRepository.save(row);
        boardEvents.rowsChanged(saved.getBoard());
        return rowMapper.apply(saved);
    }

    private int nextPosition(Board board) {
        return rowRepository.findMaxPosition(board).orElse(0) + 1;
    }

    public RowDto patchRow(User caller, RowDto rowDto, Integer id) {
        var existingRow = findRow(caller, id);
        boardService.requireWritable(caller, existingRow.getBoard());

        if (rowDto.name() != null) {
            existingRow.setName(rowDto.name());
        }
        if (rowDto.wipLimit() != null) {
            existingRow.setWipLimit(rowDto.wipLimit());
        }
        if (rowDto.position() != null) {
            existingRow.setPosition(rowDto.position());
        }
        return saveAndAnnounce(existingRow);
    }

    public void deleteRow(User caller, Integer id) {
        var row = findRow(caller, id);
        boardService.requireWritable(caller, row.getBoard());

        taskRepository.detachFromRow(row);
        if (row.getTasks() != null) {
            row.getTasks().clear();
        }

        rowRepository.delete(row);
        boardEvents.rowsChanged(row.getBoard());
    }

    public RowDto getRowById(User caller, Integer id) {
        return rowMapper.apply(findRow(caller, id));
    }

    public RowDto updateRowPosition(User caller, Integer id, Integer position) {
        var row = findRow(caller, id);
        boardService.requireWritable(caller, row.getBoard());
        row.setPosition(position);
        return saveAndAnnounce(row);
    }

    public List<RowDto> reorderRows(User caller, List<Integer> orderedIds) {
        if (orderedIds.size() != Set.copyOf(orderedIds).size()) {
            throw new GlobalException(ExceptionIdentifier.INVALID_REORDER,
                    "The same swimlane appears more than once in the requested order");
        }

        var rows = orderedIds.stream().map(id -> findRow(caller, id)).toList();
        boardService.requireWritable(caller, rows.get(0).getBoard());
        rows.forEach(row -> boardService.requireSameBoard(rows.get(0).getBoard(), row.getBoard()));

        var reordered = new ArrayList<RowDto>(rows.size());
        for (int position = 0; position < rows.size(); position++) {
            var row = rows.get(position);
            row.setPosition(position);
            reordered.add(saveAndAnnounce(row));
        }
        return reordered;
    }

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
