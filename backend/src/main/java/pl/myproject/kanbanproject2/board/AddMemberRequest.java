package pl.myproject.kanbanproject2.board;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Adds a member by the address they signed up with.
 *
 * <p>By address rather than by id, because the owner knows a colleague's email and has no way to
 * learn their user id — {@code GET /api/users} now lists only people they already share a board
 * with, which is the whole point of this change.
 */
public record AddMemberRequest(@NotBlank @Email @Size(max = 255) String email) {
}
