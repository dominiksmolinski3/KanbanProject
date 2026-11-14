package pl.myproject.kanbanproject2.layout.column;

import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class ColumnMapper implements Function<BoardColumn, ColumnDto> {

    @Override
    public ColumnDto apply(BoardColumn column) {
        if (column == null) {
            return null;
        }
        return new ColumnDto(column.getId(), column.getName(), column.getPosition(), column.getWipLimit());
    }
}