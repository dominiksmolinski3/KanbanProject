package pl.myproject.kanbanproject2.board;

import java.util.List;

/**
 * A board as the client sees it.
 *
 * <p>{@code owned} is computed for the caller rather than left to the client to derive from
 * {@code ownerId}: the UI hides the owner-only controls on it, and "compare these two ids" is
 * exactly the kind of check that is easy to write once and forget on the next screen.
 *
 * <p>{@code role} is the caller's own {@link BoardRole} - {@code MEMBER} for the owner too, since an
 * owner's write access does not depend on the stored role at all. The client disables every write
 * action when it reads {@code VIEWER}, and the server enforces the same thing regardless of what the
 * client does with it.
 */
public record BoardDto(
        Integer id,
        String name,
        Integer ownerId,
        boolean owned,
        List<BoardMemberDto> members,
        BoardRole role) {
}
