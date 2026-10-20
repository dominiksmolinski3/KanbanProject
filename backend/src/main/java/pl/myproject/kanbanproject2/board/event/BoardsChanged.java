package pl.myproject.kanbanproject2.board.event;

import pl.myproject.kanbanproject2.user.User;

import java.util.Collection;
import java.util.List;

public record BoardsChanged(List<User> people) {

    public static BoardsChanged forEveryoneIn(Collection<User> people) {
        return new BoardsChanged(List.copyOf(people));
    }
}
