package pl.myproject.kanbanproject2.chat;

/**
 * Why one message of the sender's own was not sent, as a translation key the client renders
 * through {@code t()}. Nothing composed in Java, for the reason the activity feed states: a
 * sentence written here is one the other eight bundles cannot translate.
 */
public record ChatRefusal(String reason) {

    public static final String EMPTY_MESSAGE = "chat.errors.empty";
    public static final String MESSAGE_TOO_LONG = "chat.errors.tooLong";
    /**
     * A viewer trying to post to a board's conversation. Read-only means read-only consistently
     * rather than carving chat out of it (FEAT-08); answered rather than dropped, since the sender
     * already knows they are a viewer and a silent drop would look like a delivery failure.
     */
    public static final String READ_ONLY_BOARD = "chat.errors.readOnly";
}
