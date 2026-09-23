package pl.myproject.kanbanproject2.chat;

import java.util.List;

public record ChatMessageResults(List<ChatMessageDto> messages,
                                 int page,
                                 int size,
                                 long totalEntries,
                                 int totalPages) {
}
