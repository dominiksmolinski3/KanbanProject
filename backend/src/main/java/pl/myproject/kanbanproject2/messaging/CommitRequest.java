package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CommitRequest(
        @NotNull Long deviceId,
        @NotNull @PositiveOrZero Long epoch,
        @NotNull @Size(min = 1, max = MessageDeliveryService.MAX_HANDSHAKE_BYTES) byte[] body,
        @Size(max = MessageDeliveryService.MAX_HANDSHAKE_BYTES) byte[] welcome,
        @Size(max = MessageDeliveryService.MAX_CLAIM) List<@NotNull Long> welcomeDeviceIds) {
}
