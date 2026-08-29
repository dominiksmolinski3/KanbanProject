package pl.myproject.kanbanproject2.config.security;

import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.LoginUserDto;
import pl.myproject.kanbanproject2.user.auth.RegisterUserDto;
import pl.myproject.kanbanproject2.user.auth.VerifyUserDto;

import java.security.SecureRandom;
import java.time.LocalDateTime;

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
    public void signup(RegisterUserDto input) {
        if (userRepository.findByEmail(input.getEmail()).isPresent()) {
            log.info("Signup for an address that already has an account; answering as if it were new");
            return;
        }

        User user = new User(input.getUsername(), input.getEmail(), passwordEncoder.encode(input.getPassword()));
        user.setVerificationCode(generateVerificationCode());
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(VERIFICATION_CODE_TTL_MINUTES));
        user.setEnabled(false);
        sendVerificationEmail(user);
        userRepository.save(user);
    }

    /**
     * An unknown address is reported as an invalid code rather than as an unknown user — the two
     * are the same fact from the caller's side, and only one of them names an account.
     */
    public void verifyUser(VerifyUserDto input) {
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
        userRepository.save(user);
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
    public LoginResponse login(LoginUserDto input) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(input.getEmail(), input.getPassword())
            );
        } catch (BadCredentialsException e) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CREDENTIALS, e);
        }

        User user = userRepository.findByEmail(input.getEmail())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.INVALID_CREDENTIALS));

        String jwtToken = jwtService.generateToken(user);
        return new LoginResponse(jwtToken, jwtService.getExpirationTime());
    }

    /**
     * Sends a fresh code when the address has an account still waiting to be verified, and does
     * nothing otherwise. The two "otherwise" cases — no such account, and an account that is
     * already verified — were a 404 and a 400, so between them they partitioned every address in
     * the world into three answerable states. Now they are one.
     */
    public void resendVerificationCode(String email) {
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null || user.isEnabled()) {
            log.info("Resend requested for an address with no account pending verification; answering as if it had one");
            return;
        }

        user.setVerificationCode(generateVerificationCode());
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(VERIFICATION_CODE_TTL_MINUTES));
        sendVerificationEmail(user);
        userRepository.save(user);
    }

    private void sendVerificationEmail(User user) {
        String subject = "Account Verification";
        String htmlMessage = "<html>"
                + "<body style=\"font-family: Arial, sans-serif;\">"
                + "<div style=\"background-color: #f5f5f5; padding: 20px;\">"
                + "<h2 style=\"color: #333;\">Welcome to our app!</h2>"
                + "<p style=\"font-size: 16px;\">Please enter the verification code below to continue:</p>"
                + "<div style=\"background-color: #fff; padding: 20px; border-radius: 5px; box-shadow: 0 0 10px rgba(0,0,0,0.1);\">"
                + "<h3 style=\"color: #333;\">Verification Code:</h3>"
                + "<p style=\"font-size: 18px; font-weight: bold; color: #007bff;\"> " + user.getVerificationCode() + "</p>"
                + "</div>"
                + "</div>"
                + "</body>"
                + "</html>";

        try {
            emailService.sendVerificationEmail(user.getEmail(), subject, htmlMessage);
        } catch (MessagingException e) {
            log.error("Failed to send verification email to {}", user.getEmail(), e);
            throw new GlobalException(ExceptionIdentifier.EMAIL_SEND_FAILED, e);
        }
    }

    private String generateVerificationCode() {
        return String.valueOf(RANDOM.nextInt(900000) + 100000);
    }
}
