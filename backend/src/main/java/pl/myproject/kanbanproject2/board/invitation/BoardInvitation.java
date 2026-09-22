package pl.myproject.kanbanproject2.board.invitation;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardRole;
import pl.myproject.kanbanproject2.user.User;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * An offer of membership that the person offered has to take up. This replaces
 * {@code POST /boards/{id}/members}, which put an account on a board immediately and answered with
 * the member list — letting an owner diff it to learn whether an address had an account here.
 *
 * <p><b>It names an address, not a user.</b> That lets one be created for somebody who has not
 * signed up yet — the invitation waits, and {@code GET /invitations} finds it the first time they
 * log in — rather than creating an unverified account on their behalf, which would let any account
 * occupy an arbitrary address and lock its real owner out of signup.
 *
 * <p>{@code invitedBy} is nullable for the same reason {@link Board}'s owner is: deleting that
 * account must not silently withdraw an invitation that is still good.
 */
@NoArgsConstructor
@Setter
@Getter
@Entity
@Table(name = "board_invitations")
public class BoardInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    /** Stored lower-cased by {@link #normaliseEmail}, so the unique index in V14 is a plain one. */
    @jakarta.persistence.Column(nullable = false)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by_id")
    private User invitedBy;

    @Enumerated(EnumType.STRING)
    @jakarta.persistence.Column(nullable = false, length = 16)
    private InvitationStatus status = InvitationStatus.PENDING;

    /**
     * The role the invitee joins at if they accept - carried on the offer itself rather than
     * decided afterward, so an owner can invite somebody specifically as a viewer. {@code V20}
     * defaults every column-less row to {@link BoardRole#MEMBER}, which is also this field's own
     * default for the same backward-compatibility reason: the invite-creation UI predates a choice.
     */
    @Enumerated(EnumType.STRING)
    @jakarta.persistence.Column(nullable = false, length = 16)
    private BoardRole role = BoardRole.MEMBER;

    @jakarta.persistence.Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @jakarta.persistence.Column(name = "responded_at")
    private LocalDateTime respondedAt;

    /** Joins as a {@link BoardRole#MEMBER}, the default every invitation had before this column. */
    public BoardInvitation(Board board, String email, User invitedBy) {
        this(board, email, invitedBy, BoardRole.MEMBER);
    }

    public BoardInvitation(Board board, String email, User invitedBy, BoardRole role) {
        this.board = board;
        this.email = normaliseEmail(email);
        this.invitedBy = invitedBy;
        this.role = role == null ? BoardRole.MEMBER : role;
    }

    /**
     * The stored form of an address: lower-cased and trimmed, because it's matched against the
     * address on an account and matching case would mean an invitation to {@code Ann@example.test}
     * that Ann, whose provider treats the local part case-insensitively, can never see.
     */
    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Marks the end of this invitation's life. Only a pending one can be ended. */
    public void resolveAs(InvitationStatus outcome) {
        this.status = outcome;
        this.respondedAt = LocalDateTime.now();
    }
}
