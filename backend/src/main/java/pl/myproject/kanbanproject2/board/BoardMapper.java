package pl.myproject.kanbanproject2.board;

import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserDto;
import pl.myproject.kanbanproject2.user.UserMapper;

import java.util.Comparator;
import java.util.List;

/**
 * Unlike the other mappers this one is not a bare {@code Function}: a board renders differently
 * depending on who is asking, so the caller is part of the input.
 */
@Component
public class BoardMapper {

    private final UserMapper userMapper;

    public BoardMapper(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public BoardDto apply(Board board, User caller) {
        if (board == null) {
            return null;
        }

        List<UserDto> members = board.everyone().stream()
                .sorted(Comparator.comparing(User::getId))
                .map(userMapper)
                .toList();

        return new BoardDto(
                board.getId(),
                board.getName(),
                board.getOwner() == null ? null : board.getOwner().getId(),
                board.isOwnedBy(caller),
                members);
    }
}
