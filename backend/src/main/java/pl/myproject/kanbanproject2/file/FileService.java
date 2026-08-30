package pl.myproject.kanbanproject2.file;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.io.IOException;

@RequiredArgsConstructor
@Transactional
@Service
public class FileService {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    private final FileRepository fileRepository;

    public File saveFile(User caller, MultipartFile file) {
        validateFile(file);

        try {
            String sanitizedFileName = StringUtils.cleanPath(file.getOriginalFilename());

            File fileEntity = new File(
                    sanitizedFileName,
                    file.getContentType().trim(),
                    file.getBytes(),
                    caller
            );
            return fileRepository.save(fileEntity);
        } catch (IOException e) {
            throw new GlobalException(ExceptionIdentifier.FILE_UPLOAD_FAILED, e);
        }
    }

    public File getFile(User caller, Long id) {
        return findOwnFile(caller, id);
    }

    public void deleteFile(User caller, Long id) {
        fileRepository.delete(findOwnFile(caller, id));
    }

    /**
     * A file belongs to whoever uploaded it, and to nobody else.
     *
     * <p>These ids are small sequential integers, so before there was an owner column to check,
     * {@code GET /api/files/1..n} read every upload in the deployment and {@code DELETE} destroyed
     * them. A row with no owner - anything uploaded before this column existed, other than an
     * avatar, which V5 can trace back through {@code users.avatar_id} - is treated as belonging to
     * nobody. Guessing an owner would be worse than admitting there isn't one.
     */
    private File findOwnFile(User caller, Long id) {
        var file = fileRepository.findById(id).orElseThrow(() -> fileNotFound(id));
        if (!file.isOwnedBy(caller)) {
            throw fileNotFound(id);
        }
        return file;
    }

    private GlobalException fileNotFound(Long id) {
        return new GlobalException(ExceptionIdentifier.FILE_NOT_FOUND,
                "File not found with id: " + id);
    }

    private void validateFile(MultipartFile file) {
        if (file == null) {
            throw new IllegalArgumentException("A file is required");
        }
        if (file.isEmpty() || file.getSize() <= 0) {
            throw new IllegalArgumentException("The uploaded file must not be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("The uploaded file exceeds the maximum allowed size of 10 MB");
        }
        if (!StringUtils.hasText(file.getOriginalFilename())) {
            throw new IllegalArgumentException("The uploaded file must have a valid file name");
        }
        String sanitizedFileName = StringUtils.cleanPath(file.getOriginalFilename());
        if (!StringUtils.hasText(sanitizedFileName) || sanitizedFileName.contains("..")) {
            throw new IllegalArgumentException("The uploaded file name is invalid");
        }
        if (!StringUtils.hasText(file.getContentType())) {
            throw new IllegalArgumentException("The uploaded file must have a content type");
        }
    }
}
