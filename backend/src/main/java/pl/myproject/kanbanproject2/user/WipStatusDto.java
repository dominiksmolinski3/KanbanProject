package pl.myproject.kanbanproject2.user;

public record WipStatusDto(Integer userId, Integer wipLimit, int assignedCount, boolean withinLimit) {
}
