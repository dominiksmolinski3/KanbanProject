package pl.myproject.kanbanproject2.task.attachment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskAttachmentBlobsTest {

    @Test
    @DisplayName("owns the prefix attachment blobs are written under, and answers which names a row holds")
    void answersFromTheRows() {
        TaskAttachmentRepository repository = mock(TaskAttachmentRepository.class);
        List<String> asked = List.of("tasks/1/a", "tasks/1/b");
        when(repository.findBlobNamesIn(asked)).thenReturn(List.of("tasks/1/a"));
        TaskAttachmentBlobs owner = new TaskAttachmentBlobs(repository);

        assertThat(owner.prefix()).isEqualTo("tasks/");
        assertThat(owner.referenced(asked)).containsExactly("tasks/1/a");
    }
}
