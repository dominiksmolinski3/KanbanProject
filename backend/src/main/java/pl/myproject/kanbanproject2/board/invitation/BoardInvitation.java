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
import pl.myproject.kanbanproject2.user.User;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * An offer of membership that the person offered has to take up.
 *
 * <p>Membership used to be something an owner assigned: {@code POST /boards/{id}/members} put an
 * account on a board immediately, and answered with the board - so an owner could compare the
 * member list before and after and learn whether an address had an account here. This row is the
 * fix for both halves at once. It is created for any address, so the response says nothing about
 * accounts; and it does nothing until the invitee accepts, so nobody is added to a board they
 * never agreed to be on.
 *
 * <p><b>It names an address, not a user.</b> That is what lets one be created for somebody who has
 * not signed up yet - the invitation waits, and {@code GET /invitations} finds it the first time
 * they log in. The alternative the report originally sketched, creating an unverified account on
 * their behalf, would hand any account the ability to occupy an arbitrary address and lock its
 * real owner out of signup.
 *
 * <p>{@code invitedBy} is nullable for the same reason {@link Board}'s owner is: the account that
 * sent it can be deleted, and losing the row with it would silently withdraw an invitation that
 * is still perfectly good.
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

    @jakarta.persistence.Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @jakarta.persistence.Column(name = "responded_at")
    private LocalDateTime respondedAt;

    public BoardInvitation(Board board, String email, User invitedBy) {
        this.board = board;
        this.email = normaliseEmail(email);
        this.invitedBy = invitedBy;
    }

    /**
     * The stored form of an address.
     *
     * <p>Lower-cased and trimmed, because the invitation is matched against the address on an
     * account and a person typing a colleague's address into a form types it however they please.
     * The domain half is case-insensitive by specification and the local half is case-sensitive by
     * specification and case-insensitive at every provider anybody here uses; matching case would
     * mean an invitation to {@code Ann@example.test} that Ann can never see.
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
