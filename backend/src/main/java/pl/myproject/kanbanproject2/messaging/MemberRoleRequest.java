package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;

public record MemberRoleRequest(@NotNull MemberRole role) {
}
