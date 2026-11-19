package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ClaimRequest(
        @NotNull @Size(min = 1, max = MessageDeliveryService.MAX_CLAIM) List<@NotNull Long> deviceIds) {
}
