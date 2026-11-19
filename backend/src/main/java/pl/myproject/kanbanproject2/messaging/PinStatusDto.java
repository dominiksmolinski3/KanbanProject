package pl.myproject.kanbanproject2.messaging;

public record PinStatusDto(boolean available, boolean set, int attemptsLeft) {
}
