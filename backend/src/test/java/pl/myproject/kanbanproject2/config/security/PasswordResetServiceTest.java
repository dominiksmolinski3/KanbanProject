package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.ChangePasswordRequest;
import pl.myproject.kanbanproject2.user.auth.ResetPasswordRequest;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A forgotten password used to be an unrecoverable account. These pin the three properties that
 * make the recovery path worth having rather than a second way in: the code is stored hashed, it
 * is single-use and time-bounded, and asking for one tells the caller nothing about whether the
 * address has an account.
 *
 * <p>A real {@link BCryptPasswordEncoder} is used rather than a mock. The point of hashing the
 * code is that the stored value is not the code, and only a real encoder can demonstrate that.
 */
class PasswordResetServiceTest {

    private static final String KNOWN = "known@example.test";
    private static final String UNKNOWN = "unknown@example.test";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private EmailService emailService;
    private RefreshTokenService refreshTokenService;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        emailService = mock(EmailService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        service = new PasswordResetService(userRepository, passwordEncoder, emailService, refreshTokenService);

        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    private User user(String email, boolean enabled) {
        var user = new User("someone", email, passwordEncoder.encode("old-password"));
        user.setId(1);
        user.setEnabled(enabled);
        return user;
    }

    /** The plaintext code as it went out by mail. */
    private String mailedCode() {
        var code = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPasswordResetCode(any(), code.capture(), anyLong(), any());
        assertThat(SIX_DIGITS.matcher(code.getValue()).matches())
                .as("the mail carries a six-digit code").isTrue();
        return code.getValue();
    }

    @Nested
    @DisplayName("asking for a reset")
    class Requesting {

        @Test
        @DisplayName("an address with an account is mailed a code")
        void knownAddressIsMailed() {
            var existing = user(KNOWN, true);
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(existing));

            service.requestReset(KNOWN);

            assertThat(mailedCode()).hasSize(6);
            assertThat(existing.getPasswordResetExpiresAt()).isAfter(LocalDateTime.now());
        }

        @Test
        @DisplayName("the stored code is a hash, not the code itself")
        void codeIsStoredHashed() {
            var existing = user(KNOWN, true);
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(existing));

            service.requestReset(KNOWN);
            String sent = mailedCode();

            assertThat(existing.getPasswordResetCode())
                    .as("read access to the users table must not be enough to reset an account")
                    .isNotEqualTo(sent);
            assertThat(passwordEncoder.matches(sent, existing.getPasswordResetCode())).isTrue();
        }

        @Test
        @DisplayName("an address with no account is answered the same way, and mails nothing")
        void unknownAddressIsSilent() {
            when(userRepository.findByEmail(UNKNOWN)).thenReturn(Optional.empty());

            assertThatCode(() -> service.requestReset(UNKNOWN)).doesNotThrowAnyException();

            verifyNoInteractions(emailService);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("the password is untouched until a code is actually redeemed")
        void requestingDoesNotChangeThePassword() {
            var existing = user(KNOWN, true);
            String before = existing.getPassword();
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(existing));

            service.requestReset(KNOWN);

            assertThat(existing.getPassword()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("redeeming a code")
    class Redeeming {

        private User prepared(boolean enabled) {
            var existing = user(KNOWN, enabled);
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(existing));
            service.requestReset(KNOWN);
            return existing;
        }

        @Test
        @DisplayName("the right code sets the new password and clears the code")
        void rightCodeResets() {
            var existing = prepared(true);
            String code = mailedCode();

            service.resetPassword(new ResetPasswordRequest(KNOWN, code, "brand-new-password"));

            assertThat(passwordEncoder.matches("brand-new-password", existing.getPassword())).isTrue();
            assertThat(existing.getPasswordResetCode()).isNull();
            assertThat(existing.getPasswordResetExpiresAt()).isNull();
        }

        @Test
        @DisplayName("a code is single use - the second attempt is refused")
        void codeIsSingleUse() {
            prepared(true);
            String code = mailedCode();

            service.resetPassword(new ResetPasswordRequest(KNOWN, code, "brand-new-password"));

            assertThatThrownBy(() -> service.resetPassword(
                    new ResetPasswordRequest(KNOWN, code, "another-password")))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_RESET_CODE);
        }

        @Test
        @DisplayName("a wrong code is refused and leaves the password alone")
        void wrongCodeIsRefused() {
            var existing = prepared(true);
            String before = existing.getPassword();

            assertThatThrownBy(() -> service.resetPassword(
                    new ResetPasswordRequest(KNOWN, "000000", "attacker-password")))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_RESET_CODE);

            assertThat(existing.getPassword()).isEqualTo(before);
        }

        @Test
        @DisplayName("an expired code is refused, and cleared so it cannot be ground down")
        void expiredCodeIsRefusedAndCleared() {
            var existing = prepared(true);
            String code = mailedCode();
            existing.setPasswordResetExpiresAt(LocalDateTime.now().minusMinutes(1));

            assertThatThrownBy(() -> service.resetPassword(
                    new ResetPasswordRequest(KNOWN, code, "brand-new-password")))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.RESET_CODE_EXPIRED);

            assertThat(existing.getPasswordResetCode()).isNull();
        }

        @Test
        @DisplayName("an address with no account reports an invalid code, not an unknown user")
        void unknownAddressReportsAnInvalidCode() {
            when(userRepository.findByEmail(UNKNOWN)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resetPassword(
                    new ResetPasswordRequest(UNKNOWN, "123456", "brand-new-password")))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_RESET_CODE);
        }

        @Test
        @DisplayName("an account with no reset in flight reports exactly the same thing")
        void noResetInFlightReportsTheSameThing() {
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(user(KNOWN, true)));

            assertThatThrownBy(() -> service.resetPassword(
                    new ResetPasswordRequest(KNOWN, "123456", "brand-new-password")))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_RESET_CODE);
        }

        @Test
        @DisplayName("redeeming a code on an unverified account enables it - the code proved the same thing")
        void resettingVerifiesAnUnverifiedAccount() {
            var existing = prepared(false);
            existing.setVerificationCode("111111");
            String code = mailedCode();

            service.resetPassword(new ResetPasswordRequest(KNOWN, code, "brand-new-password"));

            assertThat(existing.isEnabled())
                    .as("otherwise the one person who just proved they own the mailbox is stranded")
                    .isTrue();
            assertThat(existing.getVerificationCode()).isNull();
        }
    }

    @Nested
    @DisplayName("changing a password from inside the app")
    class Changing {

        @Test
        @DisplayName("the right current password changes it")
        void rightCurrentPasswordChangesIt() {
            var existing = user(KNOWN, true);
            when(userRepository.findById(1)).thenReturn(Optional.of(existing));

            service.changePassword(existing, new ChangePasswordRequest("old-password", "a-new-password"));

            assertThat(passwordEncoder.matches("a-new-password", existing.getPassword())).isTrue();
        }

        @Test
        @DisplayName("a wrong current password is refused, so a borrowed token cannot lock the owner out")
        void wrongCurrentPasswordIsRefused() {
            var existing = user(KNOWN, true);
            String before = existing.getPassword();

            assertThatThrownBy(() -> service.changePassword(existing,
                    new ChangePasswordRequest("not-the-password", "a-new-password")))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_CREDENTIALS);

            assertThat(existing.getPassword()).isEqualTo(before);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("changing deliberately cancels a reset already in flight")
        void changingCancelsAPendingReset() {
            var existing = user(KNOWN, true);
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(existing));
            when(userRepository.findById(1)).thenReturn(Optional.of(existing));
            service.requestReset(KNOWN);
            assertThat(existing.getPasswordResetCode()).isNotNull();

            service.changePassword(existing, new ChangePasswordRequest("old-password", "a-new-password"));

            assertThat(existing.getPasswordResetCode()).isNull();
            assertThat(existing.getPasswordResetExpiresAt()).isNull();
        }

        @Test
        @DisplayName("changing a password ends every session the account had, including this one")
        void changingWithdrawsEverySession() {
            var existing = user(KNOWN, true);
            when(userRepository.findById(1)).thenReturn(Optional.of(existing));

            service.changePassword(existing, new ChangePasswordRequest("old-password", "a-new-password"));

            // The reason someone changes a password they have not forgotten is that somebody else
            // knows it. Leaving that person's sessions running would mean the change did nothing
            // to the thing it was for.
            verify(refreshTokenService).revokeAllFor(existing);
        }

        @Test
        @DisplayName("a refused change withdraws nothing - a wrong guess is not a logout button")
        void arefusedChangeWithdrawsNothing() {
            var existing = user(KNOWN, true);

            assertThatThrownBy(() -> service.changePassword(existing,
                    new ChangePasswordRequest("not-the-password", "a-new-password")))
                    .isInstanceOf(GlobalException.class);

            verifyNoInteractions(refreshTokenService);
        }
    }

    @Nested
    @DisplayName("what a reset does to sessions already running")
    class SessionsAfterAReset {

        @Test
        @DisplayName("redeeming a code withdraws every session, which is the point of resetting")
        void redeemingWithdrawsEverySession() {
            var existing = user(KNOWN, true);
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(existing));
            service.requestReset(KNOWN);

            service.resetPassword(new ResetPasswordRequest(KNOWN, mailedCode(), "a-new-password"));

            verify(refreshTokenService).revokeAllFor(existing);
        }

        @Test
        @DisplayName("a wrong code withdraws nothing")
        void aWrongCodeWithdrawsNothing() {
            var existing = user(KNOWN, true);
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(existing));
            service.requestReset(KNOWN);

            assertThatThrownBy(() -> service.resetPassword(
                    new ResetPasswordRequest(KNOWN, "000000", "a-new-password")))
                    .isInstanceOf(GlobalException.class);

            verifyNoInteractions(refreshTokenService);
        }
    }
}
