package pl.myproject.kanbanproject2.layout.row;

import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class RowMapper implements Function<Row, RowDto> {

    @Override
    public RowDto apply(Row row) {
        if (row == null) {
            return null;
        }
        return new RowDto(row.getId(), row.getName(), row.getPosition(), row.getWipLimit());
    }
}