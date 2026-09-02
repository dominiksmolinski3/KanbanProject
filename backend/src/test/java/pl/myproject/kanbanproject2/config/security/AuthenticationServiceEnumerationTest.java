package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
import pl.myproject.kanbanproject2.user.auth.RegisterUserDto;
import pl.myproject.kanbanproject2.user.auth.VerifyUserDto;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The unauthenticated routes used to answer a different status for every state an address could
 * be in - 409 for one that exists, 404 for one that does not, 400 for one already verified - which
 * between them partitioned every address in the world into answerable buckets. The rate limiter
 * slowed that down; it did not close it, because a list is worth checking slowly.
 *
 * <p>These tests are written as differences rather than as outcomes: what matters is not that a
 * particular call succeeds, but that two calls a caller could use to tell two states apart are
 * indistinguishable from the outside.
 */
class AuthenticationServiceEnumerationTest {

    private static final String KNOWN = "known@example.test";
    private static final String UNKNOWN = "unknown@example.test";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthenticationManager authenticationManager;
    private EmailService emailService;
    private JwtService jwtService;
    private RefreshTokenService refreshTokenService;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authenticationManager = mock(AuthenticationManager.class);
        emailService = mock(EmailService.class);
        jwtService = mock(JwtService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        service = new AuthenticationService(userRepository, passwordEncoder,
                authenticationManager, emailService, jwtService, refreshTokenService);

        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        // Issuing is somebody else's contract; every path that reaches a session needs one back,
        // and the cases below that care about its contents restub it.
        when(refreshTokenService.issue(any(), any()))
                .thenReturn(new RefreshTokenService.Issued("a-refresh-token", 12L));
    }

    private static User user(String email, boolean enabled) {
        var user = new User("someone", email, "hashed");
        user.setEnabled(enabled);
        return user;
    }

    private static RegisterUserDto registration(String email) {
        var dto = new RegisterUserDto();
        dto.setUsername("someone");
        dto.setEmail(email);
        dto.setPassword("correct-horse");
        return dto;
    }

    private static VerifyUserDto verification(String email, String code) {
        var dto = new VerifyUserDto();
        dto.setEmail(email);
        dto.setVerificationCode(code);
        return dto;
    }

    @Nested
    @DisplayName("signup")
    class Signup {

        @Test
        @DisplayName("a new address is registered and mailed a code")
        void newAddressIsRegistered() throws Exception {
            when(userRepository.findByEmail(UNKNOWN)).thenReturn(Optional.empty());

            service.signup(registration(UNKNOWN));

            verify(userRepository).save(any(User.class));
            verify(emailService).sendVerificationEmail(any(), any(), any());
        }

        @Test
        @DisplayName("an address that already has an account is answered the same way, and nothing happens")
        void collisionIsSilent() throws Exception {
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(user(KNOWN, true)));

            assertThatCode(() -> service.signup(registration(KNOWN))).doesNotThrowAnyException();

            verify(userRepository, never()).save(any());
            verify(emailService, never()).sendVerificationEmail(any(), any(), any());
        }

        @Test
        @DisplayName("neither call throws, so the two cases are indistinguishable to the caller")
        void bothCasesLookIdentical() {
            when(userRepository.findByEmail(UNKNOWN)).thenReturn(Optional.empty());
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(user(KNOWN, false)));

            assertThatCode(() -> service.signup(registration(UNKNOWN))).doesNotThrowAnyException();
            assertThatCode(() -> service.signup(registration(KNOWN))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("the existing account is left alone - no password rewrite, no new code")
        void collisionDoesNotTouchTheExistingAccount() {
            var existing = user(KNOWN, true);
            existing.setVerificationCode(null);
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(existing));

            service.signup(registration(KNOWN));

            assertThat(existing.getPassword()).isEqualTo("hashed");
            assertThat(existing.getVerificationCode()).isNull();
            assertThat(existing.isEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("resend")
    class Resend {

        @Test
        @DisplayName("an account still awaiting verification gets a fresh code")
        void pendingAccountGetsACode() throws Exception {
            var pending = user(KNOWN, false);
            pending.setVerificationCode("111111");
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(pending));

            service.resendVerificationCode(KNOWN);

            assertThat(pending.getVerificationCode()).isNotEqualTo("111111");
            assertThat(pending.getVerificationCodeExpiresAt()).isAfter(LocalDateTime.now());
            verify(emailService).sendVerificationEmail(any(), any(), any());
        }

        @Test
        @DisplayName("an unknown address is not a 404 any more, and sends nothing")
        void unknownAddressIsSilent() throws Exception {
            when(userRepository.findByEmail(UNKNOWN)).thenReturn(Optional.empty());

            assertThatCode(() -> service.resendVerificationCode(UNKNOWN)).doesNotThrowAnyException();

            verify(emailService, never()).sendVerificationEmail(any(), any(), any());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("an already-verified address is not a 400 any more, and sends nothing")
        void verifiedAddressIsSilent() throws Exception {
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(user(KNOWN, true)));

            assertThatCode(() -> service.resendVerificationCode(KNOWN)).doesNotThrowAnyException();

            verify(emailService, never()).sendVerificationEmail(any(), any(), any());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("all three states answer the same, so resend confirms nothing about an address")
        void allThreeStatesLookIdentical() {
            var pending = user(KNOWN, false);
            pending.setVerificationCode("111111");

            when(userRepository.findByEmail(UNKNOWN)).thenReturn(Optional.empty());
            when(userRepository.findByEmail("verified@example.test"))
                    .thenReturn(Optional.of(user("verified@example.test", true)));
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(pending));

            assertThatCode(() -> service.resendVerificationCode(UNKNOWN)).doesNotThrowAnyException();
            assertThatCode(() -> service.resendVerificationCode("verified@example.test")).doesNotThrowAnyException();
            assertThatCode(() -> service.resendVerificationCode(KNOWN)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("verify")
    class Verify {

        @Test
        @DisplayName("an unknown address reports an invalid code, not an unknown user")
        void unknownAddressReportsAnInvalidCode() {
            when(userRepository.findByEmail(UNKNOWN)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.verifyUser(verification(UNKNOWN, "123456"), DeviceContext.unknown()))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_VERIFICATION_CODE);
        }

        @Test
        @DisplayName("a wrong code on a real account reports exactly the same thing")
        void wrongCodeReportsTheSameThing() {
            var pending = user(KNOWN, false);
            pending.setVerificationCode("111111");
            pending.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(5));
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.verifyUser(verification(KNOWN, "222222"), DeviceContext.unknown()))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_VERIFICATION_CODE);
        }

        @Test
        @DisplayName("the right code still verifies the account and clears the code")
        void rightCodeVerifies() {
            var pending = user(KNOWN, false);
            pending.setVerificationCode("111111");
            pending.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(5));
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(pending));

            service.verifyUser(verification(KNOWN, "111111"), DeviceContext.unknown());

            assertThat(pending.isEnabled()).isTrue();
            assertThat(pending.getVerificationCode()).isNull();
            assertThat(pending.getVerificationCodeExpiresAt()).isNull();
        }

        @Test
        @DisplayName("the right code signs the account in, rather than sending it back to the login form")
        void rightCodeReturnsASession() {
            var pending = user(KNOWN, false);
            pending.setVerificationCode("111111");
            pending.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(5));
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(pending));
            when(jwtService.generateToken(pending)).thenReturn("signed-access-token");
            when(jwtService.getExpirationTime()).thenReturn(900000L);
            when(refreshTokenService.getExpirationTime()).thenReturn(2592000000L);

            var session = service.verifyUser(verification(KNOWN, "111111"), DeviceContext.unknown());

            assertThat(session.token()).isEqualTo("signed-access-token");
            assertThat(session.refreshToken()).isEqualTo("a-refresh-token");
        }

        @Test
        @DisplayName("a code that does not check out issues no session")
        void aFailedVerificationIssuesNothing() {
            var pending = user(KNOWN, false);
            pending.setVerificationCode("111111");
            pending.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(5));
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.verifyUser(verification(KNOWN, "222222"), DeviceContext.unknown()))
                    .isInstanceOf(GlobalException.class);

            assertThat(pending.isEnabled()).isFalse();
            verifyNoInteractions(refreshTokenService);
        }

        @Test
        @DisplayName("an expired code is still reported as expired - the account is already known to the caller")
        void expiredCodeIsStillDistinct() {
            var pending = user(KNOWN, false);
            pending.setVerificationCode("111111");
            pending.setVerificationCodeExpiresAt(LocalDateTime.now().minusMinutes(1));
            when(userRepository.findByEmail(KNOWN)).thenReturn(Optional.of(pending));

            assertThatThrownBy(() -> service.verifyUser(verification(KNOWN, "111111"), DeviceContext.unknown()))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.VERIFICATION_CODE_EXPIRED);
        }
    }

    @Nested
    @DisplayName("the removed identifiers")
    class RemovedIdentifiers {

        @Test
        @DisplayName("no identifier remains that could report a membership fact")
        void oracleIdentifiersAreGone() {
            var names = java.util.Arrays.stream(ExceptionIdentifier.values())
                    .map(Enum::name)
                    .toList();

            assertThat(names)
                    .doesNotContain("USER_ALREADY_EXISTS", "ACCOUNT_NOT_VERIFIED", "ACCOUNT_ALREADY_VERIFIED");
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("an address with no account is an invalid credential, not a missing user")
        void unknownAddressIsAnInvalidCredential() {
            var login = new pl.myproject.kanbanproject2.user.auth.LoginUserDto();
            login.setEmail(UNKNOWN);
            login.setPassword("correct-horse");
            when(userRepository.findByEmail(UNKNOWN)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login(login, DeviceContext.unknown()))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_CREDENTIALS);

            verifyNoInteractions(jwtService);
        }
    }
}
