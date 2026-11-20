package pl.myproject.kanbanproject2.messaging;

public record PushConfigDto(String webPushKey, boolean fcm) {
}
