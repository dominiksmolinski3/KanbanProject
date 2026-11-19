package pl.myproject.kanbanproject2.messaging;

import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class MlsMessageMapper implements Function<MlsMessage, MlsMessageDto> {

    @Override
    public MlsMessageDto apply(MlsMessage message) {
        return new MlsMessageDto(message.getSeq(), message.getEpoch(), message.getKind(),
                message.getSender() == null ? null : message.getSender().getId(),
                message.getSenderDevice() == null ? null : message.getSenderDevice().getId(),
                message.getBody(), message.getCreatedAt());
    }
}
