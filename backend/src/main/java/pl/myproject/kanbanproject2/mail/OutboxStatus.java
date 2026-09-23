package pl.myproject.kanbanproject2.mail;

public enum OutboxStatus {
    PENDING,

    SENDING,

    SENT,

    FAILED,

    DROPPED
}
