package pl.myproject.kanbanproject2.chat;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pl.myproject.kanbanproject2.board.Board;

import java.time.LocalDateTime;

/**
 * One message, stored so it can be read back - which until {@code V18} it could not be, since no
 * route and no repository method ever looked at this table. {@code ChatHistoryController} is what
 * makes the row worth writing.
 *
 * <p><b>Exactly one of {@code board} and {@code recipientId} is set.</b> A board message belongs to
 * the board and is read by everyone on it; a direct message belongs to the pair and is read by the
 * two of them. There is no third kind, and in particular no global room - that was the hole
 * {@code V18} closed.
 */
@Entity
@Table(name = "chat_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Enumerated(EnumType.STRING)
    private MessageType type;

    @jakarta.persistence.Column(columnDefinition = "TEXT")
    private String content;

    /** The sender's account name, which is their email - the JWT subject the server stamps on. */
    private String sender;

    /** Set on a board message, null on a direct one. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id")
    private Board board;

    private LocalDateTime timestamp;

    /** Set on a direct message, null on a board one. The recipient's account name. */
    private String recipientId;
}
