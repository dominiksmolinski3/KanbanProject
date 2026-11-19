package pl.myproject.kanbanproject2.messaging;

public record ContactDto(Integer id, String name, String email, boolean hasAvatar, boolean messaging) {
}
