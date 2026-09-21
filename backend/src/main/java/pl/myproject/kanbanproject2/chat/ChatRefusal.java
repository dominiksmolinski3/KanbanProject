package pl.myproject.kanbanproject2.chat;

/**
 * Why one message of the sender's own was not sent, as a translation key the client renders
 * through {@code t()}. Nothing composed in Java, for the reason the activity feed states: a
 * sentence written here is one the other eight bundles cannot translate.
 */
public record ChatRefusal(String reason) {

    public static final String EMPTY_MESSAGE = "chat.errors.empty";
    public static final String MESSAGE_TOO_LONG = "chat.errors.tooLong";
}
