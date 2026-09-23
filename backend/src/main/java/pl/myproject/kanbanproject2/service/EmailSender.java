package pl.myproject.kanbanproject2.service;

public interface EmailSender {
    String send(EmailMessage message);

    default boolean deliversMessages() {
        return true;
    }
}
