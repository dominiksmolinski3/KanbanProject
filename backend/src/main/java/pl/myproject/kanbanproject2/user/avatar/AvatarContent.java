package pl.myproject.kanbanproject2.user.avatar;

import java.io.InputStream;

public record AvatarContent(InputStream stream, String contentType, long sizeBytes) {
}
