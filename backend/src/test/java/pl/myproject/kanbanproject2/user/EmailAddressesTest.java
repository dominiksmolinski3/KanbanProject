package pl.myproject.kanbanproject2.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class EmailAddressesTest {

    @Test
    @DisplayName("an address is stored trimmed and lower-cased, however it was typed")
    void theAccountStoresTheNormalisedAddress() {
        var signedUp = new User("Bob", "  Bob@Example.TEST ", "hash");
        var renamed = new User();
        renamed.setEmail("ADA@example.test");

        assertThat(signedUp.getEmail()).isEqualTo("bob@example.test");
        assertThat(renamed.getEmail()).isEqualTo("ada@example.test");
        assertThat(EmailAddresses.normalise(null)).isNull();
    }

    @Test
    @DisplayName("every lookup by address normalises first, so another case finds the same account")
    void lookupsNormaliseTheAddress() {
        UserRepository repository = mock(UserRepository.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        var bob = new User("Bob", "bob@example.test", "hash");
        when(repository.findUserByEmail("bob@example.test")).thenReturn(Optional.of(bob));

        assertThat(repository.findByEmail(" BOB@Example.test")).contains(bob);
        verify(repository).findUserByEmail("bob@example.test");
    }
}
