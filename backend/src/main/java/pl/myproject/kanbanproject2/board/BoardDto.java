package pl.myproject.kanbanproject2.board;

import java.util.List;

public record BoardDto(
        Integer id,
        String name,
        Integer ownerId,
        boolean owned,
        List<BoardMemberDto> members,
        BoardRole role) {
}
