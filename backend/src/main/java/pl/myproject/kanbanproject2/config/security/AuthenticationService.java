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
     * Registers the address if it is new, and does nothing at all if it is not. Answering
     * {@code 409 USER_ALREADY_EXISTS} made this endpoint a membership oracle - an unauthenticated
     * caller could check addresses one at a time - so the caller is told nothing either way, and the
     * password-reset flow is what a genuine account owner is meant to fall back to. The branch below
     * is logged so the collision is at least visible server-side.
     */
    @Transactional
    public void signup(RegisterUserDto input) {
        if (userRepository.findByEmail(input.getEmail()).isPresent()) {
            log.info("Signup for an address that already has an account; answering as if it were new");
            return;
        }

        User user = new User(input.getUsername(), input.getEmail(), passwordEncoder.encode(input.getPassword()));
        // A guess, and the only one available - SupportedLocales.normalise falls back to English
        // rather than refusing, since a signup is not the place to argue about a header.
        user.setLocale(SupportedLocales.normalise(input.getLocale()));
        user.setVerificationCode(generateVerificationCode());
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(VERIFICATION_CODE_TTL_MINUTES));
        user.setEnabled(false);
        userRepository.save(user);
        sendVerificationEmail(user);
    }

    /**
     * An unknown address is reported as an invalid code rather than an unknown user - the same fact
     * from the caller's side. Answers with a session, exactly as {@link #login} does, because the
     * code is a credential spent on redemption; asking for the password again afterwards would be a
     * second credential for a fact the caller has just proved.
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
     * Every failure here is one status: {@code 401 INVALID_CREDENTIALS}, deliberately including an
     * unverified account. {@code DaoAuthenticationProvider} throws {@code DisabledException} for a
     * disabled user before comparing the password, and {@code GlobalExceptionHandler} maps every
     * {@code AuthenticationException} to the same 401 - so a distinct "not verified" status would be
     * readable without knowing the password, the enumeration oracle this route avoids.
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
     * Exchanges a refresh token for a new pair, without asking for the password again - the route
     * that makes a short access token affordable. {@link RefreshTokenService#rotate} withdraws the
     * presented token in the same transaction that issues its replacement.
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
     * nothing otherwise - the "otherwise" cases used to be a 404 and a 400, partitioning every
     * address into three answerable states instead of the one this collapses them to.
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
