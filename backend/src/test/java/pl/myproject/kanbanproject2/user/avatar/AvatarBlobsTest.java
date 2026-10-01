package pl.myproject.kanbanproject2.user.avatar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AvatarBlobsTest {

    @Test
    @DisplayName("owns the prefix avatar blobs are written under, and answers which names an account holds")
    void answersFromTheAccounts() {
        UserRepository repository = mock(UserRepository.class);
        List<String> asked = List.of("avatars/1/a", "avatars/1/b");
        when(repository.findAvatarBlobNamesIn(asked)).thenReturn(List.of("avatars/1/b"));
        AvatarBlobs owner = new AvatarBlobs(repository);

        assertThat(owner.prefix()).isEqualTo("avatars/");
        assertThat(owner.referenced(asked)).containsExactly("avatars/1/b");
    }
}
