package pl.myproject.kanbanproject2.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    private final UserMapper mapper = new UserMapper();

    @Test
    @DisplayName("an account says whether it has an avatar, so a client does not ask for one that is not there")
    void saysWhetherThereIsAnAvatar() {
        var user = new User("Ada", "ada@example.test", "hashed");
        user.setId(1);

        assertThat(mapper.apply(user).hasAvatar()).isFalse();

        user.setAvatarBlobName("avatars/1/3f2c");

        assertThat(mapper.apply(user).hasAvatar()).isTrue();
        assertThat(mapper.apply(null)).isNull();
    }
}
