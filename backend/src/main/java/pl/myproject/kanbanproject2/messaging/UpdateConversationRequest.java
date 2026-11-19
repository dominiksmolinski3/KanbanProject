package pl.myproject.kanbanproject2.messaging;

public record UpdateConversationRequest(Boolean muted, Boolean pinned, Boolean archived,
                                        HistoryVisibility historyVisibility, WhoCanAdd whoCanAdd) {
}
