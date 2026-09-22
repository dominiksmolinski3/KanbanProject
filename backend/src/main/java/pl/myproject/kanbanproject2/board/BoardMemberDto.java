package pl.myproject.kanbanproject2.board;

/**
 * One row of a board's member list, as the client renders it - the same flat shape
 * {@code UserDto} always had, with the role {@code V20} added to {@code board_members} alongside it.
 * A separate record rather than a field bolted onto {@code UserDto}, since a role is a fact about
 * membership on <em>this</em> board, not about the account.
 */
public record BoardMemberDto(
        Integer id,
        String email,
        String name,
        Integer wipLimit,
        String locale,
        BoardRole role) {
}
