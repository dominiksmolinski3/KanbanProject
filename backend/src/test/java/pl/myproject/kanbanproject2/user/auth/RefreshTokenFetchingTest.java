package pl.myproject.kanbanproject2.user.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenFetchingTest {

    @Test
    @DisplayName("the token lookup a refresh starts from fetches its user, which is read after the transaction")
    void lookupFetchesTheUser() throws NoSuchMethodException {
        EntityGraph graph = RefreshTokenRepository.class
                .getDeclaredMethod("findByTokenHash", String.class)
                .getAnnotation(EntityGraph.class);

        assertThat(graph).as("findByTokenHash carries an @EntityGraph").isNotNull();
        assertThat(graph.attributePaths()).contains("user");
    }
}
