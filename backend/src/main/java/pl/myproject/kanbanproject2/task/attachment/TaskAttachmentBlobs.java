package pl.myproject.kanbanproject2.task.attachment;

import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.storage.BlobOwner;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Component
class TaskAttachmentBlobs implements BlobOwner {

    private final TaskAttachmentRepository attachments;

    TaskAttachmentBlobs(TaskAttachmentRepository attachments) {
        this.attachments = attachments;
    }

    @Override
    public String prefix() {
        return TaskAttachmentService.BLOB_PREFIX;
    }

    @Override
    public Set<String> referenced(Collection<String> blobNames) {
        return new HashSet<>(attachments.findBlobNamesIn(blobNames));
    }
}
