package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record MessageRequest(
        @NotNull Long deviceId,
        @NotNull @PositiveOrZero Long epoch,
        @NotNull @Size(min = 1) byte[] body) {
}
