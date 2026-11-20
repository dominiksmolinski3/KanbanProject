package pl.myproject.kanbanproject2.messaging;

import java.time.Instant;
import java.util.List;

public record ConversationDto(Long id, ConversationKind kind, Integer boardId, String boardName, byte[] groupId, long epoch, boolean needsCommit,
                              HistoryVisibility historyVisibility, WhoCanAdd whoCanAdd, long lastSeq,
                              Instant createdAt, Instant lastActivityAt, long unread, MembershipDto me,
                              List<MemberDto> members) {
}
