package pl.myproject.kanbanproject2.board;

import pl.myproject.kanbanproject2.user.UserDto;

import java.util.List;

/**
 * A board as the client sees it.
 *
 * <p>{@code owned} is computed for the caller rather than left to the client to derive from
 * {@code ownerId}: the UI hides the owner-only controls on it, and "compare these two ids" is
 * exactly the kind of check that is easy to write once and forget on the next screen.
 */
public record BoardDto(
        Integer id,
        String name,
        Integer ownerId,
        boolean owned,
        List<UserDto> members) {
}
