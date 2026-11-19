package pl.myproject.kanbanproject2.messaging;

public record MembershipDto(MemberRole role, MemberState state, long joinedSeq, long readSeq, boolean muted,
                            boolean pinned, boolean archived) {
}
