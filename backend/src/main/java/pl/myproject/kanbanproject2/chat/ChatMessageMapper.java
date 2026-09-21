package pl.myproject.kanbanproject2.chat;

import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class ChatMessageMapper implements Function<Chat, ChatMessageDto> {

    @Override
    public ChatMessageDto apply(Chat chat) {
        return new ChatMessageDto(
                chat.getId(),
                chat.getType(),
                chat.getContent(),
                chat.getSender(),
                chat.getBoard() == null ? null : chat.getBoard().getId(),
                chat.getRecipientId(),
                chat.getTimestamp());
    }
}
