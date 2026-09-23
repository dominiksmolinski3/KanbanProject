package pl.myproject.kanbanproject2.board.invitation;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.myproject.kanbanproject2.board.Board;

import java.util.List;
import java.util.Optional;

public interface BoardInvitationRepository extends JpaRepository<BoardInvitation, Integer> {

    List<BoardInvitation> findByBoardAndStatusOrderByIdAsc(Board board, InvitationStatus status);

    Optional<BoardInvitation> findByBoardAndEmailAndStatus(Board board, String email,
                                                           InvitationStatus status);

    List<BoardInvitation> findByEmailAndStatusOrderByIdAsc(String email, InvitationStatus status);

    List<BoardInvitation> findByBoard(Board board);
}
