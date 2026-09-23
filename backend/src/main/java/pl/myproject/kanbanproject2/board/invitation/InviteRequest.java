package pl.myproject.kanbanproject2.board.invitation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import pl.myproject.kanbanproject2.board.BoardRole;

public record InviteRequest(@NotBlank @Email @Size(max = 255) String email, BoardRole role) {
    public InviteRequest(String email) {
        this(email, null);
    }
}
