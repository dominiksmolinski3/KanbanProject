package pl.myproject.kanbanproject2.task.activity;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

/**
 * The read side of the feed: one board, newest first, paged.
 *
 * <p>Paged for the reason the search route is and the board listing is not. A board renders every
 * card it has and is bounded by what a team will put on one; a feed is bounded by nothing at all -
 * it only ever grows, and a board a year old would return a year of entries to draw twenty of
 * them. So the same numbers and the same refusal: 25 by default, {@link #MAX_PAGE_SIZE} at most,
 * and asking for more is a {@code 400} rather than a silent clamp, because a caller handed fewer
 * rows than it asked for cannot tell that from a short last page.
 */
@RequiredArgsConstructor
@Transactional
@Service
public class TaskActivityService {

    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 100;

    private final TaskActivityRepository repository;
    private final TaskActivityMapper mapper;
    private final BoardService boardService;

    public TaskActivityResults feed(User caller, Integer boardId, Integer page, Integer size) {
        int wantedPage = page == null ? 0 : page;
        int wantedSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (wantedPage < 0 || wantedSize < 1 || wantedSize > MAX_PAGE_SIZE) {
            throw new GlobalException(ExceptionIdentifier.INVALID_ACTIVITY_REQUEST,
                    "page must be 0 or more and size must be between 1 and " + MAX_PAGE_SIZE);
        }

        var board = boardService.resolve(caller, boardId);
        var found = repository.findByBoardOrderByOccurredAtDescIdDesc(
                board, PageRequest.of(wantedPage, wantedSize));

        return new TaskActivityResults(
                found.getContent().stream().map(mapper).toList(),
                wantedPage,
                wantedSize,
                found.getTotalElements(),
                found.getTotalPages());
    }
}
