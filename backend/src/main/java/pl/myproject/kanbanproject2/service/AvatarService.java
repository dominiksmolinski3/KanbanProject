package pl.myproject.kanbanproject2.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.file.File;
import pl.myproject.kanbanproject2.file.FileRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

@RequiredArgsConstructor
@Transactional
@Service
public class AvatarService {

    private static final long MAX_AVATAR_SIZE = 1024 * 1024;

    /*
     * An explicit list, not `image/*`: that prefix admits image/svg+xml, and an SVG is a document
     * with script in it. The stored type is echoed back by UserController.getAvatar on the app's
     * own origin, so a single upload was stored XSS against every viewer's token.
     */
    private static final Set<String> ALLOWED_AVATAR_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    private final FileRepository fileRepository;
    private final UserRepository userRepository;

    public void uploadAvatar(Integer userId, MultipartFile file) {
        if (file.isEmpty() || !ALLOWED_AVATAR_TYPES.contains(normalisedContentType(file))) {
            throw new GlobalException(ExceptionIdentifier.INVALID_AVATAR_FILE_TYPE);
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new GlobalException(ExceptionIdentifier.AVATAR_FILE_TOO_LARGE);
        }

        var user = findUser(userId);

        if (user.getAvatar() != null) {
            fileRepository.delete(user.getAvatar());
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new GlobalException(ExceptionIdentifier.FILE_UPLOAD_FAILED, e);
        }

        // The declared type is attacker-controlled, so it only narrows the set; the bytes decide.
        if (!matchesDeclaredType(normalisedContentType(file), bytes)) {
            throw new GlobalException(ExceptionIdentifier.INVALID_AVATAR_FILE_TYPE);
        }

        var newAvatar = new File(file.getOriginalFilename(), normalisedContentType(file), bytes);
        fileRepository.save(newAvatar);
        user.setAvatar(newAvatar);
        userRepository.save(user);
    }

    public byte[] getAvatar(Integer userId) {
        var user = findUser(userId);
        if (user.getAvatar() == null || user.getAvatar().getData() == null) {
            throw new GlobalException(ExceptionIdentifier.AVATAR_NOT_FOUND);
        }
        return user.getAvatar().getData();
    }

    public String getAvatarContentType(Integer userId) {
        var user = findUser(userId);
        if (user.getAvatar() == null) {
            throw new GlobalException(ExceptionIdentifier.AVATAR_NOT_FOUND);
        }
        return user.getAvatar().getType();
    }

    public void deleteAvatar(Integer userId) {
        var user = findUser(userId);
        if (user.getAvatar() == null) {
            throw new GlobalException(ExceptionIdentifier.AVATAR_NOT_FOUND);
        }

        var avatarToDelete = user.getAvatar();
        user.setAvatar(null);
        userRepository.save(user);
        fileRepository.delete(avatarToDelete);
    }

    private static String normalisedContentType(MultipartFile file) {
        var contentType = file.getContentType();
        if (contentType == null) {
            return "";
        }
        // Browsers may append parameters, e.g. "image/jpeg; charset=binary".
        var separator = contentType.indexOf(';');
        var bare = separator < 0 ? contentType : contentType.substring(0, separator);
        return bare.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesDeclaredType(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/png" -> startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/jpeg" -> startsWith(bytes, 0xFF, 0xD8, 0xFF);
            case "image/gif" -> startsWith(bytes, 'G', 'I', 'F', '8');
            // RIFF....WEBP - the four size bytes at offset 4 are skipped.
            case "image/webp" -> startsWith(bytes, 'R', 'I', 'F', 'F')
                    && bytes.length >= 12
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default -> false;
        };
    }

    private static boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private User findUser(Integer userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND,
                        "User not found with id: " + userId));
    }
}
