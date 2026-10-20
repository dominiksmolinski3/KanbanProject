package pl.myproject.kanbanproject2.user.auth;

import org.junit.jupiter.api.DisplayName;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;

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

    @Test
    @DisplayName("the lookup locks the row, so two refreshes of one token cannot both rotate it")
    void lookupLocksTheRow() throws NoSuchMethodException {
        Lock lock = RefreshTokenRepository.class
                .getDeclaredMethod("findByTokenHash", String.class)
                .getAnnotation(Lock.class);

        assertThat(lock)
                .as("without a row lock, two refreshes arriving together both read the token as live, "
                        + "both rotate it, and the chain forks with no reuse ever detected")
                .isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }
}
