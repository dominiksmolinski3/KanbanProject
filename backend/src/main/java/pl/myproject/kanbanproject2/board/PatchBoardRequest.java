package pl.myproject.kanbanproject2.board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PatchBoardRequest(@NotBlank @Size(max = 255) String name) {
}
