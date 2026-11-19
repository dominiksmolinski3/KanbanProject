package pl.myproject.kanbanproject2.messaging;

import java.time.Instant;

public record MlsMessageDto(long seq, long epoch, MlsMessageKind kind, Integer senderId, Long senderDeviceId,
                            byte[] body, Instant createdAt) {
}
