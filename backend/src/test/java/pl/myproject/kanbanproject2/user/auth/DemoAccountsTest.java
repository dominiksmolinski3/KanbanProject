package pl.myproject.kanbanproject2.user.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DemoAccountsTest {

    private static User account(String email) {
        return new User("someone", email, "hashed");
    }

    @Test
    @DisplayName("an address on the list is a demo account whatever its case")
    void matchesIgnoringCase() {
        var demo = new DemoAccounts(List.of(" Demo@Example.test ", ""));

        assertThat(demo.isDemo(account("demo@example.test"))).isTrue();
        assertThat(demo.isDemo(account("DEMO@EXAMPLE.TEST"))).isTrue();
        assertThat(demo.isDemo(account("someone@example.test"))).isFalse();
    }

    @Test
    @DisplayName("an empty list makes no account a demo one")
    void emptyListMatchesNothing() {
        assertThat(new DemoAccounts(List.of()).isDemo(account("demo@example.test"))).isFalse();
    }
}
