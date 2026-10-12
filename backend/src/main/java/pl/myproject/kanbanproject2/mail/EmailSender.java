package pl.myproject.kanbanproject2.mail;

public interface EmailSender {
    String send(EmailMessage message);

    default boolean deliversMessages() {
        return true;
    }
}
