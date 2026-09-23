package pl.myproject.kanbanproject2.chat;

public record ChatRefusal(String reason) {
    public static final String EMPTY_MESSAGE = "chat.errors.empty";
    public static final String MESSAGE_TOO_LONG = "chat.errors.tooLong";
    public static final String READ_ONLY_BOARD = "chat.errors.readOnly";
}
