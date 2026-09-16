package pl.myproject.kanbanproject2.config.security;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.service.EmailDeliveryException;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.SupportedLocales;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.ChangePasswordRequest;
import pl.myproject.kanbanproject2.user.auth.ResetPasswordRequest;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * Recovering an account, and changing a password from inside one. This is also what the uniform
 * signup response depends on: signup can no longer tell someone they already have an account, so
 * this is the path that has to be able to reach them instead.
 */
@RequiredArgsConstructor
@Transactional
@Service
@Slf4j
public class PasswordResetService {

    /**
     * Shorter than the fifteen minutes a verification code gets. A verification code only proves an
     * address is reachable; this one changes a credential, so the window in which a copy left in an
     * inbox is worth stealing should be as small as the flow tolerates.
     */
    private static final int RESET_CODE_TTL_MINUTES = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;

    /**
     * Mails a reset code if the address has an account, and does nothing if it does not - answering
     * differently would make this a membership oracle on an endpoint that needs no authentication
     * at all, the same reason signup and resend answer uniformly.
     */
    public void requestReset(String email) {
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            log.info("Password reset requested for an address with no account; answering as if it had one");
            return;
        }

        String code = generateCode();
        // Stored hashed. A verification code is a nuisance if it leaks; this one is a credential,
        // and the users table is exactly what an attacker with read access already has.
        user.setPasswordResetCode(passwordEncoder.encode(code));
        user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(RESET_CODE_TTL_MINUTES));
        userRepository.save(user);

        sendResetEmail(user, code);
    }

    /**
     * Redeems a reset code and sets the new password. Every failure is one status - an unknown
     * address, an unrequested reset, an expired code and a wrong code are four facts and one
     * answer, since three of them describe the account rather than the request.
     */
    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.INVALID_RESET_CODE));

        if (user.getPasswordResetCode() == null || user.getPasswordResetExpiresAt() == null) {
            throw new GlobalException(ExceptionIdentifier.INVALID_RESET_CODE);
        }
        if (user.getPasswordResetExpiresAt().isBefore(LocalDateTime.now())) {
            // Cleared on the way out, so an expired code cannot be ground down by repetition.
            clearResetCode(user);
            userRepository.save(user);
            throw new GlobalException(ExceptionIdentifier.RESET_CODE_EXPIRED);
        }
        if (!passwordEncoder.matches(request.resetCode(), user.getPasswordResetCode())) {
            throw new GlobalException(ExceptionIdentifier.INVALID_RESET_CODE);
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        clearResetCode(user);
        // Withdraws every session the account had - the route where it matters most, since a
        // password reset usually means someone else has it. The reset cannot retract access
        // tokens already issued, which is why that expiry is short.
        refreshTokenService.revokeAllFor(user);

        // Redeeming the code proves control of the mailbox, the same thing verification proves;
        // leaving an unverified account disabled would strand someone who just demonstrated that.
        if (!user.isEnabled()) {
            log.info("Password reset completed for an unverified account; enabling it");
            user.setEnabled(true);
            user.setVerificationCode(null);
            user.setVerificationCodeExpiresAt(null);
        }

        userRepository.save(user);
    }

    /**
     * Changes the password of the account the caller is already signed in as. Requires the current
     * password rather than trusting the token alone, since knowledge of the password is a stronger
     * claim than possession of a token and this is the operation a borrowed one could otherwise use
     * to lock the owner out.
     */
    public void changePassword(User currentUser, ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.currentPassword(), currentUser.getPassword())) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CREDENTIALS);
        }

        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND));

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        // A reset in flight is stale the moment the password changes deliberately.
        clearResetCode(user);
        userRepository.save(user);
        // Including the caller's own session: there is no way to tell it from anybody else's here,
        // since the request carries an access token rather than a refresh one.
        refreshTokenService.revokeAllFor(user);
    }

    private static void clearResetCode(User user) {
        user.setPasswordResetCode(null);
        user.setPasswordResetExpiresAt(null);
    }

    private void sendResetEmail(User user, String code) {
        try {
            emailService.sendPasswordResetCode(user.getEmail(), code, RESET_CODE_TTL_MINUTES,
                    SupportedLocales.toLocale(user.getLocale()));
        } catch (EmailDeliveryException e) {
            log.error("Failed to send password reset email to {}", user.getEmail(), e);
            throw new GlobalException(ExceptionIdentifier.EMAIL_SEND_FAILED, e);
        }
    }

    private String generateCode() {
        return String.valueOf(RANDOM.nextInt(900000) + 100000);
    }
}
