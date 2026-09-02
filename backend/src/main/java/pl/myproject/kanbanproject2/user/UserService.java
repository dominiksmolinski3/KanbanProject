package pl.myproject.kanbanproject2.user;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

@RequiredArgsConstructor
@Transactional
@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final TaskRepository taskRepository;
    private final BoardService boardService;

    /**
     * The people the caller shares a board with, rather than every account on the deployment.
     *
     * <p>This route feeds the assignee picker and the members screen, and it used to answer with
     * the whole {@code users} table — every address, every display name, to anyone who could log
     * in. Narrowing it is half of the same change as the board checks: an account you cannot work
     * with is an account you have no reason to be able to enumerate.
     *
     * <p>The caller is always included, whether or not they are on a board yet, because the UI
     * looks itself up in this list.
     */
    public List<UserDto> getVisibleUsers(User caller) {
        /*
         * Keyed on id. The caller arrives from the JWT filter and the peers from the persistence
         * context, so the same account is two objects and User inherits identity equality - a
         * plain Set listed whoever was asking twice, which is what running it turned up.
         */
        var visible = new LinkedHashMap<Integer, User>();
        if (caller != null) {
            visible.put(caller.getId(), caller);
        }
        boardService.peersOf(caller).forEach(peer -> visible.putIfAbsent(peer.getId(), peer));
        return visible.values().stream()
                .sorted(Comparator.comparing(User::getId))
                .map(userMapper)
                .toList();
    }

    public UserDto getUserById(User caller, Integer id) {
        return userMapper.apply(findVisibleUser(caller, id));
    }

    /** Throws unless the caller may see this account at all. See {@link #findVisibleUser}. */
    public void requireVisibleUser(User caller, Integer id) {
        findVisibleUser(caller, id);
    }

    /**
     * An account the caller shares no board with answers as one that does not exist. Same reasoning
     * as everywhere else here: a 403 would confirm the id is in use.
     */
    private User findVisibleUser(User caller, Integer id) {
        if (caller != null && caller.getId().equals(id)) {
            return caller;
        }
        var user = userRepository.findById(id).orElseThrow(() -> userNotFound(id));
        boolean sharesABoard = boardService.peersOf(caller).stream()
                .anyMatch(peer -> peer.getId().equals(id));
        if (!sharesABoard) {
            throw userNotFound(id);
        }
        return user;
    }

    /**
     * Unassigns the user from every task before removing the account.
     *
     * <p>{@code User.tasks} is the inverse side of {@code user_task} — {@link Task} owns the join
     * table — so nothing Hibernate does on the user's behalf clears those rows, and the delete
     * failed on the foreign key for any user who was assigned to anything. The tasks themselves
     * stay: they belong to the board, not to the account.
     */
    public void deleteUser(Integer id) {
        var user = userRepository.findById(id).orElseThrow(() -> userNotFound(id));

        for (Task task : List.copyOf(user.getTasks())) {
            task.getUsers().remove(user);
            taskRepository.save(task);
        }
        user.getTasks().clear();

        userRepository.delete(user);
    }

    public UserDto patchUser(UserDto userDto, Integer id) {
        var existingUser = userRepository.findById(id).orElseThrow(() -> userNotFound(id));

        if (userDto.email() != null) {
            existingUser.setEmail(userDto.email());
        }
        if (userDto.name() != null) {
            existingUser.setName(userDto.name());
        }
        if (userDto.wipLimit() != null) {
            existingUser.setWipLimit(userDto.wipLimit());
        }
        if (userDto.locale() != null) {
            // Rejected rather than normalised to English. Signup guesses from a browser header and
            // falls back quietly, because a guess that misses costs nothing; this is somebody
            // choosing, and silently storing a different answer than the one they gave is worse
            // than telling them the language is not one of the nine.
            if (!SupportedLocales.isSupported(userDto.locale())) {
                throw new GlobalException(ExceptionIdentifier.UNSUPPORTED_LOCALE);
            }
            existingUser.setLocale(SupportedLocales.normalise(userDto.locale()));
        }
        return userMapper.apply(userRepository.save(existingUser));
    }

    /** A WIP limit is a property of an account, so only its owner may set it. */
    public UserDto updateWipLimit(User caller, Integer userId, Integer wipLimit) {
        if (caller == null || !caller.getId().equals(userId)) {
            throw new GlobalException(ExceptionIdentifier.NOT_ACCOUNT_OWNER);
        }
        var user = userRepository.findById(userId).orElseThrow(() -> userNotFound(userId));
        user.setWipLimit(wipLimit);
        return userMapper.apply(userRepository.save(user));
    }

    /**
     * Describes how close a user is to their WIP limit, rather than only whether they are under it.
     *
     * <p>A null limit means "no limit", so such a user is always within it.
     */
    public WipStatusDto getWipStatus(User caller, Integer userId) {
        findVisibleUser(caller, userId);
        return wipStatusOf(userId);
    }

    /**
     * The same figures without an access check, for {@code TaskService} to consult before it puts
     * somebody on a task. The caller has already been checked against the board there, and the
     * assignee against the board's membership, so there is nobody left to check here.
     */
    private WipStatusDto wipStatusOf(Integer userId) {
        var user = userRepository.findById(userId).orElseThrow(() -> userNotFound(userId));
        Integer wipLimit = user.getWipLimit();
        int assignedCount = user.getTasks().size();
        boolean withinLimit = wipLimit == null || assignedCount < wipLimit;

        return new WipStatusDto(user.getId(), wipLimit, assignedCount, withinLimit);
    }

    public boolean checkWipStatus(Integer userId) {
        return wipStatusOf(userId).withinLimit();
    }

    private GlobalException userNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.USER_NOT_FOUND,
                "User not found with id: " + id);
    }
}
