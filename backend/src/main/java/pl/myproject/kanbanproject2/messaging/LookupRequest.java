package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;

public record LookupRequest(@NotNull String email) {
}
