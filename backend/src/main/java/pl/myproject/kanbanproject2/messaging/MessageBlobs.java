package pl.myproject.kanbanproject2.messaging;

import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.storage.BlobOwner;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Component
class MessageBlobs implements BlobOwner {

    private final MessageBlobRepository blobs;

    MessageBlobs(MessageBlobRepository blobs) {
        this.blobs = blobs;
    }

    @Override
    public String prefix() {
        return MessageBlobService.BLOB_PREFIX;
    }

    @Override
    public Set<String> referenced(Collection<String> blobNames) {
        return new HashSet<>(blobs.findBlobNamesIn(blobNames));
    }
}
