package pl.myproject.kanbanproject2.user;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;

import java.util.List;

@RequiredArgsConstructor
@Transactional
@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final TaskRepository taskRepository;

    public List<UserDto> getAllUsers() {
        return userRepository.findAll().stream().map(userMapper).toList();
    }

    public UserDto getUserById(Integer id) {
        return userRepository.findById(id).map(userMapper)
                .orElseThrow(() -> userNotFound(id));
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
        return userMapper.apply(userRepository.save(existingUser));
    }

    public UserDto updateWipLimit(Integer userId, Integer wipLimit) {
        var user = userRepository.findById(userId).orElseThrow(() -> userNotFound(userId));
        user.setWipLimit(wipLimit);
        return userMapper.apply(userRepository.save(user));
    }

    /**
     * Describes how close a user is to their WIP limit, rather than only whether they are under it.
     *
     * <p>A null limit means "no limit", so such a user is always within it.
     */
    public WipStatusDto getWipStatus(Integer userId) {
        var user = userRepository.findById(userId).orElseThrow(() -> userNotFound(userId));
        Integer wipLimit = user.getWipLimit();
        int assignedCount = user.getTasks().size();
        boolean withinLimit = wipLimit == null || assignedCount < wipLimit;

        return new WipStatusDto(user.getId(), wipLimit, assignedCount, withinLimit);
    }

    public boolean checkWipStatus(Integer userId) {
        return getWipStatus(userId).withinLimit();
    }

    private GlobalException userNotFound(Integer id) {
        return new GlobalException(ExceptionIdentifier.USER_NOT_FOUND,
                "User not found with id: " + id);
    }
}
