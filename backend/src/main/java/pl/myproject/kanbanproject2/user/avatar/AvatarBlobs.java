package pl.myproject.kanbanproject2.user.avatar;

import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.storage.BlobOwner;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Component
class AvatarBlobs implements BlobOwner {

    private final UserRepository users;

    AvatarBlobs(UserRepository users) {
        this.users = users;
    }

    @Override
    public String prefix() {
        return AvatarService.BLOB_PREFIX;
    }

    @Override
    public Set<String> referenced(Collection<String> blobNames) {
        return new HashSet<>(users.findAvatarBlobNamesIn(blobNames));
    }
}
