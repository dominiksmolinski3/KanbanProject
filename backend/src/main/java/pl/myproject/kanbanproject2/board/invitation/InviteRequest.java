package pl.myproject.kanbanproject2.board.invitation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Invites the person at this address, whether or not they have an account here.
 *
 * <p>By address rather than by id, because the owner knows a colleague's email and has no way to
 * learn their user id - {@code GET /api/users} lists only people they already share a board with.
 * That was true of {@code AddMemberRequest} before it and is the reason both take an address.
 */
public record InviteRequest(@NotBlank @Email @Size(max = 255) String email) {
}
