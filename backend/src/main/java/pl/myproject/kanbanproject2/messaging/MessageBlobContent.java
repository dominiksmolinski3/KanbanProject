package pl.myproject.kanbanproject2.messaging;

import java.io.InputStream;

public record MessageBlobContent(InputStream stream, long size) {
}
