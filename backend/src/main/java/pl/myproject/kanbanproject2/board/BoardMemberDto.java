package pl.myproject.kanbanproject2.board;

public record BoardMemberDto(
        Integer id,
        String email,
        String name,
        Integer wipLimit,
        String locale,
        BoardRole role) {
}
