package pl.myproject.kanbanproject2.messaging;

import java.time.Instant;

public record MemberDto(Integer userId, String name, boolean hasAvatar, MemberRole role, MemberState state,
                        Instant joinedAt, long joinedSeq, long deliveredSeq, long readSeq) {
}
