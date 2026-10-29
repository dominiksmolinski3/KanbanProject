package pl.myproject.kanbanproject2.config.security;

import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import pl.myproject.kanbanproject2.chat.ChatRepository;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.mail.EmailService;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.ConfirmEmailChangeRequest;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
import pl.myproject.kanbanproject2.user.auth.EmailChangeRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailChangeServiceTest {
    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final DeviceContext DEVICE = new DeviceContext("203.0.113.9", "JUnit");

    private UserRepository userRepository;
    private EmailService emailService;
    private RefreshTokenService refreshTokenService;
    private AuthenticationService authenticationService;
    private ChatRepository chatRepository;
    private EmailChangeService service;
    private User account;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        emailService = mock(EmailService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        authenticationService = mock(AuthenticationService.class);
        chatRepository = mock(ChatRepository.class);
        service = new EmailChangeService(userRepository, ENCODER, emailService, refreshTokenService,
                authenticationService, chatRepository);

        account = new User("Owner", "owner@example.test", ENCODER.encode("the-password"));
        account.setId(1);
        account.setLocale("en");
        when(userRepository.findById(1)).thenReturn(Optional.of(account));
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("a request with the right password mails a code to the new address, not the old one")
    void aRequestMailsTheNewAddress() {
        service.requestChange(account, new EmailChangeRequest("  New@Example.TEST ", "the-password"));

        assertThat(account.getPendingEmail()).isEqualTo("new@example.test");
        assertThat(account.getEmailChangeCode()).matches("\\d{6}");
        assertThat(account.getEmail()).isEqualTo("owner@example.test");
        verify(emailService).sendEmailChangeCode(eq("new@example.test"), eq(account.getEmailChangeCode()),
                eq((long) EmailChangeService.CODE_TTL_MINUTES), any());
    }

    @Test
    @DisplayName("a wrong password is a 400 WRONG_PASSWORD, and nothing is stored or sent")
    void aWrongPasswordIsRefused() {
        expect(ExceptionIdentifier.WRONG_PASSWORD, () ->
                service.requestChange(account, new EmailChangeRequest("new@example.test", "guess")));

        assertThat(account.getPendingEmail()).isNull();
        verify(userRepository, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("asking for the address the account already has, in any case, is EMAIL_UNCHANGED")
    void theSameAddressIsRefused() {
        expect(ExceptionIdentifier.EMAIL_UNCHANGED, () ->
                service.requestChange(account, new EmailChangeRequest("Owner@Example.test", "the-password")));
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("an address that already has an account answers like a free one and sends nothing")
    void aTakenAddressSendsNothing() {
        when(userRepository.findByEmail("taken@example.test")).thenReturn(Optional.of(new User()));

        service.requestChange(account, new EmailChangeRequest("taken@example.test", "the-password"));

        assertThat(account.getPendingEmail()).isNull();
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("asking again for the address already pending sends no second mail")
    void aRepeatedRequestSendsOnce() {
        service.requestChange(account, new EmailChangeRequest("new@example.test", "the-password"));
        String code = account.getEmailChangeCode();

        service.requestChange(account, new EmailChangeRequest("NEW@example.test", "the-password"));

        assertThat(account.getEmailChangeCode()).isEqualTo(code);
        verify(emailService).sendEmailChangeCode(anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("the right code moves the address, ends every session and answers a new one")
    void theRightCodeChangesTheAddress() {
        pending("new@example.test", "123456", Instant.now().plus(Duration.ofMinutes(5)));
        var session = new LoginResponse("jwt", 900_000, "refresh", 1L, 7L);
        when(authenticationService.startSession(account, DEVICE)).thenReturn(session);

        var answer = service.confirmChange(account, new ConfirmEmailChangeRequest("123456"), DEVICE);

        assertThat(answer).isSameAs(session);
        assertThat(account.getEmail()).isEqualTo("new@example.test");
        assertThat(account.getPendingEmail()).isNull();
        assertThat(account.getEmailChangeCode()).isNull();
        verify(refreshTokenService).revokeAllFor(account);
    }

    @Test
    @DisplayName("the account's chat moves with the address, or its direct messages vanish from both sides")
    void theChatFollowsTheAddress() {
        pending("new@example.test", "123456", Instant.now().plus(Duration.ofMinutes(5)));

        service.confirmChange(account, new ConfirmEmailChangeRequest("123456"), DEVICE);

        verify(chatRepository).renameSender("owner@example.test", "new@example.test");
        verify(chatRepository).renameRecipient("owner@example.test", "new@example.test");
    }

    @Test
    @DisplayName("a refused confirmation leaves the chat where it is")
    void aRefusedConfirmationRenamesNothing() {
        pending("new@example.test", "123456", Instant.now().plus(Duration.ofMinutes(5)));

        assertThatThrownBy(() ->
                service.confirmChange(account, new ConfirmEmailChangeRequest("000000"), DEVICE));

        verifyNoInteractions(chatRepository);
    }

    @Test
    @DisplayName("a wrong code counts an attempt, and the fifth wrong one withdraws the pending change")
    void wrongCodesAreCounted() {
        pending("new@example.test", "123456", Instant.now().plus(Duration.ofMinutes(5)));

        for (int attempt = 1; attempt < EmailChangeService.MAX_ATTEMPTS; attempt++) {
            expect(ExceptionIdentifier.INVALID_VERIFICATION_CODE, () ->
                    service.confirmChange(account, new ConfirmEmailChangeRequest("000000"), DEVICE));
            assertThat(account.getEmailChangeAttempts()).isEqualTo(attempt);
        }
        expect(ExceptionIdentifier.INVALID_VERIFICATION_CODE, () ->
                service.confirmChange(account, new ConfirmEmailChangeRequest("000000"), DEVICE));

        assertThat(account.getPendingEmail()).isNull();
        expect(ExceptionIdentifier.INVALID_VERIFICATION_CODE, () ->
                service.confirmChange(account, new ConfirmEmailChangeRequest("123456"), DEVICE));
        assertThat(account.getEmail()).isEqualTo("owner@example.test");
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    @DisplayName("the attempt counter survives the refusal, because confirm does not roll back on it")
    void theCounterIsCommittedWithTheRefusal() throws NoSuchMethodException {
        var confirm = EmailChangeService.class.getMethod("confirmChange",
                User.class, ConfirmEmailChangeRequest.class, DeviceContext.class);

        assertThat(confirm.getAnnotation(Transactional.class).dontRollbackOn())
                .contains(GlobalException.class);
    }

    @Test
    @DisplayName("an expired code is VERIFICATION_CODE_EXPIRED and clears the pending change")
    void anExpiredCodeIsRefused() {
        pending("new@example.test", "123456", Instant.now().minus(Duration.ofMinutes(1)));

        expect(ExceptionIdentifier.VERIFICATION_CODE_EXPIRED, () ->
                service.confirmChange(account, new ConfirmEmailChangeRequest("123456"), DEVICE));

        assertThat(account.getPendingEmail()).isNull();
        assertThat(account.getEmail()).isEqualTo("owner@example.test");
    }

    @Test
    @DisplayName("an address taken by someone else since the request is refused at confirm time")
    void anAddressTakenMeanwhileIsRefused() {
        pending("new@example.test", "123456", Instant.now().plus(Duration.ofMinutes(5)));
        when(userRepository.findByEmail("new@example.test")).thenReturn(Optional.of(new User()));

        expect(ExceptionIdentifier.INVALID_VERIFICATION_CODE, () ->
                service.confirmChange(account, new ConfirmEmailChangeRequest("123456"), DEVICE));

        assertThat(account.getEmail()).isEqualTo("owner@example.test");
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    @DisplayName("confirming with nothing pending is INVALID_VERIFICATION_CODE")
    void nothingPendingIsRefused() {
        expect(ExceptionIdentifier.INVALID_VERIFICATION_CODE, () ->
                service.confirmChange(account, new ConfirmEmailChangeRequest("123456"), DEVICE));
    }

    private void pending(String address, String code, Instant expiresAt) {
        account.setPendingEmail(address);
        account.setEmailChangeCode(code);
        account.setEmailChangeExpiresAt(expiresAt);
    }

    private static void expect(ExceptionIdentifier identifier, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(identifier);
    }
}
