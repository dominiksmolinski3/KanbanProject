package pl.myproject.kanbanproject2.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.user.User;

@RestController
@RequestMapping("/conversations/{conversationId}/blobs")
@RequiredArgsConstructor
public class MessageBlobController {

    private final MessageBlobService blobs;

    @PostMapping
    public ResponseEntity<MessageBlobDto> upload(@PathVariable Long conversationId,
                                                 @RequestParam("file") MultipartFile file,
                                                 @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(blobs.upload(currentUser, conversationId, file));
    }

    @GetMapping("/{blobId}")
    public ResponseEntity<InputStreamResource> content(@PathVariable Long conversationId, @PathVariable Long blobId,
                                                       @AuthenticationPrincipal User currentUser) {
        MessageBlobContent content = blobs.content(currentUser, conversationId, blobId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(content.size())
                .body(new InputStreamResource(content.stream()));
    }
}
