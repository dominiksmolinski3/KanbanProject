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
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.ChangePasswordRequest;
import pl.myproject.kanbanproject2.user.auth.ResetPasswordRequest;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * Recovering an account, and changing a password from inside one.
 *
 * <p>Until this existed a forgotten password was an unrecoverable account: nothing reset it, and
 * the admin path ({@code PATCH /api/users/{id}}) does not touch the password field either. It is
 * also what the uniform signup response depends on — signup can no longer tell someone they
 * already have an account, so this is the path that has to be able to reach them instead.
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
     * Mails a reset code if the address has an account, and does nothing if it does not.
     *
     * <p>Answering differently for the two would make this a membership oracle on an endpoint that
     * needs no authentication at all — the same reason signup and resend answer uniformly. The
     * caller is told the same thing either way, and the difference is only ever visible to whoever
     * reads the mailbox.
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
     * Redeems a reset code and sets the new password.
     *
     * <p>Every failure is one status. An unknown address, an unrequested reset, an expired code and
     * a wrong code are four different facts and one answer, because three of them describe the
     * account rather than the request.
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
        /*
         * Every session the account had is withdrawn here, and this is the route where it matters
         * most: somebody resetting a password they did not lose is doing it because another person
         * has it, and leaving that person's sessions running would mean the reset changed nothing
         * they were using. What the reset cannot do is retract the access tokens already issued -
         * those run to their own expiry, which is the reason that expiry is now short.
         */
        refreshTokenService.revokeAllFor(user);

        /*
         * Redeeming the code proves control of the mailbox, which is the same thing the
         * verification flow proves and the only thing it proves. Leaving an unverified account
         * disabled here would strand the one person who has just demonstrated they own it, with
         * a working password they still cannot use.
         */
        if (!user.isEnabled()) {
            log.info("Password reset completed for an unverified account; enabling it");
            user.setEnabled(true);
            user.setVerificationCode(null);
            user.setVerificationCodeExpiresAt(null);
        }

        userRepository.save(user);
    }

    /**
     * Changes the password of the account the caller is already signed in as.
     *
     * <p>Requires the current password rather than trusting the token alone. That was a workaround
     * while a token could not be withdrawn at all; it stays because it is still the right rule for
     * this one route - knowledge of the password is a stronger claim than possession of a token,
     * and this is the operation that would let a borrowed one lock the owner out. What has changed
     * is what happens afterwards: the change now ends every other session the account holds.
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
        /*
         * Including the caller's own session. Changing a password is the one moment where signing
         * everyone out is the point, and there is no way to tell the caller's refresh token from
         * anybody else's here - the request carries an access token, not a refresh one. The client
         * asks for a new pair with the new password, which is a login it was going to be shown
         * anyway.
         */
        refreshTokenService.revokeAllFor(user);
    }

    private static void clearResetCode(User user) {
        user.setPasswordResetCode(null);
        user.setPasswordResetExpiresAt(null);
    }

    private void sendResetEmail(User user, String code) {
        String subject = "Password Reset";
        String htmlMessage = "<html>"
                + "<body style=\"font-family: Arial, sans-serif;\">"
                + "<div style=\"background-color: #f5f5f5; padding: 20px;\">"
                + "<h2 style=\"color: #333;\">Reset your password</h2>"
                + "<p style=\"font-size: 16px;\">Enter the code below to choose a new password. "
                + "It expires in " + RESET_CODE_TTL_MINUTES + " minutes.</p>"
                + "<div style=\"background-color: #fff; padding: 20px; border-radius: 5px; box-shadow: 0 0 10px rgba(0,0,0,0.1);\">"
                + "<h3 style=\"color: #333;\">Reset Code:</h3>"
                + "<p style=\"font-size: 18px; font-weight: bold; color: #007bff;\"> " + code + "</p>"
                + "</div>"
                + "<p style=\"font-size: 14px; color: #666;\">If you did not ask for this, nothing has "
                + "changed on your account and you can ignore this message.</p>"
                + "</div>"
                + "</body>"
                + "</html>";

        try {
            emailService.sendVerificationEmail(user.getEmail(), subject, htmlMessage);
        } catch (EmailDeliveryException e) {
            log.error("Failed to send password reset email to {}", user.getEmail(), e);
            throw new GlobalException(ExceptionIdentifier.EMAIL_SEND_FAILED, e);
        }
    }

    private String generateCode() {
        return String.valueOf(RANDOM.nextInt(900000) + 100000);
    }
}
