package pl.myproject.kanbanproject2.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;

@Entity
@Table(name = "conversation_members",
        uniqueConstraints = @UniqueConstraint(name = "uq_conversation_members",
                columnNames = {"conversation_id", "user_id"}))
public class ConversationMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private MemberState state;

    @Column(name = "joined_seq", nullable = false)
    private long joinedSeq;

    @Column(name = "read_seq", nullable = false)
    private long readSeq;

    @Column(name = "muted", nullable = false)
    private boolean muted;

    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected ConversationMember() {
    }

    public ConversationMember(Conversation conversation, User user, MemberRole role, MemberState state,
                              Instant joinedAt) {
        this.conversation = conversation;
        this.user = user;
        this.role = role;
        this.state = state;
        this.joinedSeq = conversation.getNextSeq();
        this.readSeq = conversation.getLastSeq();
        this.joinedAt = joinedAt;
    }

    public void accept() {
        this.state = MemberState.ACTIVE;
        this.archived = false;
    }

    public void readUpTo(long seq) {
        this.readSeq = Math.max(this.readSeq, seq);
    }

    public void setRole(MemberRole role) {
        this.role = role;
    }

    public void prefer(Boolean muted, Boolean pinned, Boolean archived) {
        if (muted != null) {
            this.muted = muted;
        }
        if (pinned != null) {
            this.pinned = pinned;
        }
        if (archived != null) {
            this.archived = archived;
        }
    }

    public boolean isAdmin() {
        return role == MemberRole.ADMIN;
    }

    public Long getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public User getUser() {
        return user;
    }

    public MemberRole getRole() {
        return role;
    }

    public MemberState getState() {
        return state;
    }

    public long getJoinedSeq() {
        return joinedSeq;
    }

    public long getReadSeq() {
        return readSeq;
    }

    public boolean isMuted() {
        return muted;
    }

    public boolean isPinned() {
        return pinned;
    }

    public boolean isArchived() {
        return archived;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
