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

    public List<UserDto> getVisibleUsers(User caller) {
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

    public void requireVisibleUser(User caller, Integer id) {
        findVisibleUser(caller, id);
    }

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
            if (!SupportedLocales.isSupported(userDto.locale())) {
                throw new GlobalException(ExceptionIdentifier.UNSUPPORTED_LOCALE);
            }
            existingUser.setLocale(SupportedLocales.normalise(userDto.locale()));
        }
        return userMapper.apply(userRepository.save(existingUser));
    }

    public UserDto updateWipLimit(User caller, Integer userId, Integer wipLimit) {
        if (caller == null || !caller.getId().equals(userId)) {
            throw new GlobalException(ExceptionIdentifier.NOT_ACCOUNT_OWNER);
        }
        var user = userRepository.findById(userId).orElseThrow(() -> userNotFound(userId));
        user.setWipLimit(wipLimit);
        return userMapper.apply(userRepository.save(user));
    }

    public WipStatusDto getWipStatus(User caller, Integer userId) {
        findVisibleUser(caller, userId);
        return wipStatusOf(userId);
    }

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
