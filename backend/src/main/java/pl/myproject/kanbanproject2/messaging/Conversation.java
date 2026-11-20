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
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;

@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private ConversationKind kind;

    @Column(name = "direct_key", length = 32, unique = true)
    private String directKey;

    @Column(name = "group_id", nullable = false, unique = true)
    private byte[] groupId;

    @Column(name = "epoch", nullable = false)
    private long epoch;

    @Column(name = "next_seq", nullable = false)
    private long nextSeq;

    @Column(name = "needs_commit", nullable = false)
    private boolean needsCommit;

    @Enumerated(EnumType.STRING)
    @Column(name = "history_visibility", nullable = false, length = 16)
    private HistoryVisibility historyVisibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "who_can_add", nullable = false, length = 16)
    private WhoCanAdd whoCanAdd;

    @Column(name = "disappear_after_seconds")
    private Integer disappearAfterSeconds;

    @Column(name = "board_id", unique = true)
    private Integer boardId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    protected Conversation() {
    }

    public Conversation(ConversationKind kind, String directKey, byte[] groupId, HistoryVisibility historyVisibility,
                        WhoCanAdd whoCanAdd, User createdBy, Instant createdAt) {
        this.kind = kind;
        this.directKey = directKey;
        this.groupId = groupId.clone();
        this.epoch = 0;
        this.nextSeq = 1;
        this.historyVisibility = historyVisibility;
        this.whoCanAdd = whoCanAdd;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.lastActivityAt = createdAt;
    }

    public static Conversation forBoard(Integer boardId, byte[] groupId, Instant createdAt) {
        Conversation conversation = new Conversation(ConversationKind.BOARD, null, groupId, HistoryVisibility.JOINED,
                WhoCanAdd.ADMINS, null, createdAt);
        conversation.boardId = boardId;
        return conversation;
    }

    public long take(MlsMessageKind messageKind, Instant at) {
        long seq = nextSeq++;
        if (messageKind == MlsMessageKind.COMMIT) {
            epoch++;
            needsCommit = false;
        } else {
            lastActivityAt = at;
        }
        return seq;
    }

    public void restart(byte[] newGroupId) {
        this.groupId = newGroupId.clone();
        this.epoch = 0;
        this.needsCommit = false;
    }

    public void requireCommit() {
        this.needsCommit = true;
    }

    public void disappearAfter(Integer seconds) {
        if (seconds != null) {
            this.disappearAfterSeconds = seconds == 0 ? null : seconds;
        }
    }

    public void configure(HistoryVisibility historyVisibility, WhoCanAdd whoCanAdd) {
        if (historyVisibility != null) {
            this.historyVisibility = historyVisibility;
        }
        if (whoCanAdd != null) {
            this.whoCanAdd = whoCanAdd;
        }
    }

    public Long getId() {
        return id;
    }

    public ConversationKind getKind() {
        return kind;
    }

    public Integer getBoardId() {
        return boardId;
    }

    public String getDirectKey() {
        return directKey;
    }

    public byte[] getGroupId() {
        return groupId.clone();
    }

    public long getEpoch() {
        return epoch;
    }

    public long getNextSeq() {
        return nextSeq;
    }

    public long getLastSeq() {
        return nextSeq - 1;
    }

    public boolean isNeedsCommit() {
        return needsCommit;
    }

    public HistoryVisibility getHistoryVisibility() {
        return historyVisibility;
    }

    public WhoCanAdd getWhoCanAdd() {
        return whoCanAdd;
    }

    public Integer getDisappearAfterSeconds() {
        return disappearAfterSeconds;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }
}
