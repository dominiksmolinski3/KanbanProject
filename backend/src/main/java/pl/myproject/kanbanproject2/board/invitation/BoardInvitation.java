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

    @jakarta.persistence.Column(nullable = false)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by_id")
    private User invitedBy;

    @Enumerated(EnumType.STRING)
    @jakarta.persistence.Column(nullable = false, length = 16)
    private InvitationStatus status = InvitationStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @jakarta.persistence.Column(nullable = false, length = 16)
    private BoardRole role = BoardRole.MEMBER;

    @jakarta.persistence.Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @jakarta.persistence.Column(name = "responded_at")
    private LocalDateTime respondedAt;

    public BoardInvitation(Board board, String email, User invitedBy) {
        this(board, email, invitedBy, BoardRole.MEMBER);
    }

    public BoardInvitation(Board board, String email, User invitedBy, BoardRole role) {
        this.board = board;
        this.email = normaliseEmail(email);
        this.invitedBy = invitedBy;
        this.role = role == null ? BoardRole.MEMBER : role;
    }

    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public void resolveAs(InvitationStatus outcome) {
        this.status = outcome;
        this.respondedAt = LocalDateTime.now();
    }
}
