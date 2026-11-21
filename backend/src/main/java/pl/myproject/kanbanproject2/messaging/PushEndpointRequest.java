package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;

public record PushEndpointRequest(@NotNull Long deviceId, @NotNull PushKind kind, @NotNull String endpoint,
                                  String p256dh, String auth) {
}
