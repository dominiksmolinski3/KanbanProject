package pl.myproject.kanbanproject2.storage;

import java.util.Collection;
import java.util.Set;

public interface BlobOwner {

    String prefix();

    Set<String> referenced(Collection<String> blobNames);
}
