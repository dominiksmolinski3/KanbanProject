package pl.myproject.kanbanproject2.config.security;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.service.EmailDeliveryException;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.SupportedLocales;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.ActiveDeviceDto;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
import pl.myproject.kanbanproject2.user.auth.LoginUserDto;
import pl.myproject.kanbanproject2.user.auth.RegisterUserDto;
import pl.myproject.kanbanproject2.user.auth.VerifyUserDto;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

@RequiredArgsConstructor
@Service
@Slf4j
public class AuthenticationService {

    private static final int VERIFICATION_CODE_TTL_MINUTES = 15;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    /**
     * Registers the address if it is new, and does nothing at all if it is not.
     *
     * <p>Answering {@code 409 USER_ALREADY_EXISTS} made this endpoint a membership oracle: it told
     * an unauthenticated caller, one address at a time, which of them have accounts here. The rate
     * limiter slowed that down without closing it — a list is worth checking slowly.
     *
     * <p>So the caller is told nothing either way. It follows that the collision cannot be reported
     * to the person who hit it, which is a real cost: someone who genuinely forgot they had an
     * account gets a verification mail that never arrives, and no explanation. That is what the
     * password-reset flow is for, and it is the reason this trade is only worth making once that
     * flow exists to point them at. Until then the client's wording carries it, and the branch
     * below is logged so the collision is at least visible from the server side.
     */
    @Transactional
    public void signup(RegisterUserDto input) {
        if (userRepository.findByEmail(input.getEmail()).isPresent()) {
            log.info("Signup for an address that already has an account; answering as if it were new");
            return;
        }

        User user = new User(input.getUsername(), input.getEmail(), passwordEncoder.encode(input.getPassword()));
        // A guess, and the only one available: the browser signing up is the best evidence there
        // is of what language this person reads, and it is wrong the moment they sign up from
        // somebody else's machine. SupportedLocales.normalise falls back to English rather than
        // refusing, because a signup is not the place to argue about a header.
        user.setLocale(SupportedLocales.normalise(input.getLocale()));
        user.setVerificationCode(generateVerificationCode());
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(VERIFICATION_CODE_TTL_MINUTES));
        user.setEnabled(false);
        userRepository.save(user);
        sendVerificationEmail(user);
    }

    /**
     * An unknown address is reported as an invalid code rather than as an unknown user — the two
     * are the same fact from the caller's side, and only one of them names an account.
     *
     * <p>Answers with a session, exactly as {@link #login} does, because the code is a credential:
     * it was sent to the mailbox and it is spent here. Verifying used to answer {@code 204} and
     * leave the client to ask for the password again, which is a second credential for a fact the
     * caller has just proved — and the client had nowhere to go with a body it did not get, so
     * the person who had just verified sat on the verification screen with a verified account.
     */
    public LoginResponse verifyUser(VerifyUserDto input, DeviceContext device) {
        User user = userRepository.findByEmail(input.getEmail())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.INVALID_VERIFICATION_CODE));

        if (user.getVerificationCodeExpiresAt() == null
                || user.getVerificationCodeExpiresAt().isBefore(LocalDateTime.now())) {
            throw new GlobalException(ExceptionIdentifier.VERIFICATION_CODE_EXPIRED);
        }
        if (!user.getVerificationCode().equals(input.getVerificationCode())) {
            throw new GlobalException(ExceptionIdentifier.INVALID_VERIFICATION_CODE);
        }

        user.setEnabled(true);
        user.setVerificationCode(null);
        user.setVerificationCodeExpiresAt(null);
        return issueSession(userRepository.save(user), device);
    }

    /**
     * Every failure here is one status: {@code 401 INVALID_CREDENTIALS}.
     *
     * <p>An unverified account is included in that, and not by omission. {@code
     * DaoAuthenticationProvider} runs its pre-authentication checks before it compares the
     * password, so {@link User#isEnabled()} being false throws {@code DisabledException} whether
     * the password was right or wrong — and {@code GlobalExceptionHandler} maps every
     * {@code AuthenticationException} to the same 401. A distinct "account not verified" status
     * would therefore be readable without knowing the password, which is the enumeration oracle
     * this route does not have. The explicit {@code enabled} check that used to sit here after
     * {@code authenticate()} could never run for that same reason, and is gone rather than left
     * looking like a control.
     */
    public LoginResponse login(LoginUserDto input, DeviceContext device) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(input.getEmail(), input.getPassword())
            );
        } catch (BadCredentialsException e) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CREDENTIALS, e);
        }

        User user = userRepository.findByEmail(input.getEmail())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.INVALID_CREDENTIALS));

        return issueSession(user, device);
    }

    /**
     * Exchanges a refresh token for a new pair, without asking for the password again.
     *
     * <p>This is the route that makes a short access token affordable. Rotation happens inside
     * {@link RefreshTokenService#rotate}, which withdraws the presented token as part of the same
     * transaction that issues its replacement - so the token the client sends here is spent by the
     * time it reads the answer.
     */
    public LoginResponse refresh(String refreshToken, DeviceContext device) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refreshToken, device);
        return respondWith(rotation.user(), rotation.refreshToken(), rotation.sessionId());
    }

    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    /**
     * The sessions this account can still use. A pass-through, deliberately: the controller already
     * holds this service and nothing else, and {@link RefreshTokenService} stays the only reader of
     * the table.
     */
    public List<ActiveDeviceDto> listSessions(User user) {
        return refreshTokenService.listSessionsFor(user);
    }

    /** Ends one of them by id, or answers 404 if it is not this account's to end. */
    public void revokeSession(User user, Long sessionId) {
        refreshTokenService.revokeSession(user, sessionId);
    }

    private LoginResponse issueSession(User user, DeviceContext device) {
        RefreshTokenService.Issued issued = refreshTokenService.issue(user, device);
        return respondWith(user, issued.token(), issued.sessionId());
    }

    private LoginResponse respondWith(User user, String refreshToken, Long sessionId) {
        return new LoginResponse(
                jwtService.generateToken(user),
                jwtService.getExpirationTime(),
                refreshToken,
                refreshTokenService.getExpirationTime(),
                sessionId);
    }

    /**
     * Sends a fresh code when the address has an account still waiting to be verified, and does
     * nothing otherwise. The two "otherwise" cases — no such account, and an account that is
     * already verified — were a 404 and a 400, so between them they partitioned every address in
     * the world into three answerable states. Now they are one.
     */
    @Transactional
    public void resendVerificationCode(String email) {
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null || user.isEnabled()) {
            log.info("Resend requested for an address with no account pending verification; answering as if it had one");
            return;
        }

        user.setVerificationCode(generateVerificationCode());
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(VERIFICATION_CODE_TTL_MINUTES));
        userRepository.save(user);
        sendVerificationEmail(user);
    }

    private void sendVerificationEmail(User user) {
        try {
            emailService.sendVerificationCode(
                    user.getEmail(), user.getVerificationCode(), VERIFICATION_CODE_TTL_MINUTES,
                    SupportedLocales.toLocale(user.getLocale()));
        } catch (EmailDeliveryException e) {
            log.error("Failed to send verification email to {}", user.getEmail(), e);
            throw new GlobalException(ExceptionIdentifier.EMAIL_SEND_FAILED, e);
        }
    }

    private String generateVerificationCode() {
        return String.valueOf(RANDOM.nextInt(900000) + 100000);
    }
}
