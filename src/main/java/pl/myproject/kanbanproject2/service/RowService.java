package pl.myproject.kanbanproject2.service;

import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.dto.RowDTO;
import pl.myproject.kanbanproject2.mapper.RowMapper;
import pl.myproject.kanbanproject2.model.Row;
import pl.myproject.kanbanproject2.model.Task;
import pl.myproject.kanbanproject2.repository.RowRepository;
import pl.myproject.kanbanproject2.repository.TaskRepository;

import java.util.List;
import java.util.stream.Collectors;

@Transactional
@Service
public class RowService {
    private final RowRepository rowRepository;
    private final TaskRepository taskRepository;
    private final RowMapper rowMapper;

    @Autowired
    public RowService(RowRepository rowRepository, TaskRepository taskRepository, RowMapper rowMapper) {
        this.rowRepository = rowRepository;
        this.taskRepository = taskRepository;
        this.rowMapper = rowMapper;
    }

    public List<RowDTO> getAllRows() {
        List<RowDTO> rowDTOS = rowRepository.findAll().stream()
                .map(rowMapper::apply)
                .collect(Collectors.toList());
        return rowDTOS;
    }

    public Row createRow(Row row) {
        // If position is not set, set it to the last position + 1
        if (row.getPosition() == null) {
            long count = rowRepository.count();
            row.setPosition((int) count + 1);
        }
        return rowRepository.save(row);
    }

    public RowDTO patchRow(RowDTO rowDTO, Integer id) {
        Row existingRow = rowRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Nie ma takiego wiersza"));

        if (rowDTO.name() != null) {
            existingRow.setName(rowDTO.name());
        }
        if(rowDTO.wipLimit() != null) {
            existingRow.setWipLimit(rowDTO.wipLimit());
        }
        if(rowDTO.position() != null) {
            existingRow.setPosition(rowDTO.position());
        }
        Row updatedRow = rowRepository.save(existingRow);
        return rowMapper.apply(updatedRow);
    }

    public void deleteRow(Integer id) {
        deleteRow(id, false);
    }

    @Transactional
    public void deleteRow(Integer id, boolean cascade) {
        Row row = rowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Nie ma wiersza o takim ID"));
        
        // Find all tasks associated with this row
        List<Task> tasksInRow = taskRepository.findByRowId(id);
        
        if (!tasksInRow.isEmpty()) {
            if (cascade) {
                // If cascade is true, remove row association from all tasks
                for (Task task : tasksInRow) {
                    task.setRow(null);
                    taskRepository.save(task);
                }
            } else {
                // If cascade is false, throw an exception
                throw new IllegalStateException("Cannot delete row with associated tasks. Use cascade=true to remove row associations first.");
            }
        }
        
        rowRepository.deleteById(id);
    }

    public RowDTO getRowById(Integer id) {
        return rowRepository.findById(id).
                map(rowMapper::apply).
                orElseThrow(() -> new EntityNotFoundException("Nie ma wiersza"));
    }

    public RowDTO updateRowPosition(Integer id, Integer position) {
        Row row = rowRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Nie ma wiersza o takim id"));
        row.setPosition(position);
        Row updatedRow = rowRepository.save(row);
        return rowMapper.apply(updatedRow);
    }
}