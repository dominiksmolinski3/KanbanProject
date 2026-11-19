package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AddMembersRequest(
        @NotNull @Size(min = 1, max = ConversationService.MAX_MEMBERS - 1) List<@NotNull Integer> userIds) {
}
