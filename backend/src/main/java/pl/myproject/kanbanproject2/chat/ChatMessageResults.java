package pl.myproject.kanbanproject2.chat;

import java.util.List;

/**
 * One page of scroll-back, shaped like {@code TaskActivityResults} for the same reasons: a total
 * so a short page can be told from the last one, and no derived helpers, since Jackson serialises
 * a record by its components alone.
 */
public record ChatMessageResults(List<ChatMessageDto> messages,
                                 int page,
                                 int size,
                                 long totalEntries,
                                 int totalPages) {
}
