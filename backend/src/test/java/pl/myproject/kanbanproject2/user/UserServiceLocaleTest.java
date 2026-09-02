package pl.myproject.kanbanproject2.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.TaskRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Setting the language on an account, which is the one place the tag is a choice rather than a
 * guess - and therefore the one place an unsupported one is refused rather than quietly replaced.
 *
 * <p>Signup does the opposite with the same helper, and deliberately: it reads a browser header
 * that nobody chose, so a tag it cannot use costs the person nothing to have wrong and would cost
 * them an account to have refused over.
 */
class UserServiceLocaleTest {

    private static final Integer USER_ID = 7;

    private UserRepository userRepository;
    private UserService userService;
    private User account;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        var tenant = TenancyFixtures.tenant();
        account = tenant.caller();
        account.setId(USER_ID);
        account.setLocale("en");
        userService = new UserService(userRepository, new UserMapper(), mock(TaskRepository.class),
                tenant.boardService());

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(account));
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("a supported language is stored, and comes back on the account")
    void aSupportedLanguageIsStored() {
        UserDto patched = userService.patchUser(new UserDto(null, null, null, null, "ja"), USER_ID);

        assertThat(account.getLocale()).isEqualTo("ja");
        assertThat(patched.locale()).isEqualTo("ja");
    }

    @Test
    @DisplayName("a regional tag is stored as its language, because that is all nine bundles distinguish")
    void aRegionalTagIsStoredAsItsLanguage() {
        userService.patchUser(new UserDto(null, null, null, null, "de-AT"), USER_ID);

        assertThat(account.getLocale()).isEqualTo("de");
    }

    @Test
    @DisplayName("a language with no messages is refused rather than silently turned into English")
    void anUnsupportedLanguageIsRefused() {
        assertThatThrownBy(() ->
                userService.patchUser(new UserDto(null, null, null, null, "is"), USER_ID))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.UNSUPPORTED_LOCALE);

        assertThat(account.getLocale()).isEqualTo("en");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("a patch that says nothing about the language leaves it alone")
    void anAbsentLanguageIsLeftAlone() {
        userService.patchUser(new UserDto(null, null, "Renamed", null, null), USER_ID);

        assertThat(account.getLocale()).isEqualTo("en");
        assertThat(account.getName()).isEqualTo("Renamed");
    }
}
