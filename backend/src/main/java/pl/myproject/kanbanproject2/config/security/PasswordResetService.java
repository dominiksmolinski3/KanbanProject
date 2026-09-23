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

@RequiredArgsConstructor
@Transactional
@Service
@Slf4j
public class PasswordResetService {

    private static final int RESET_CODE_TTL_MINUTES = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;

    public void requestReset(String email) {
        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            log.info("Password reset requested for an address with no account; answering as if it had one");
            return;
        }

        String code = generateCode();
        user.setPasswordResetCode(passwordEncoder.encode(code));
        user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(RESET_CODE_TTL_MINUTES));
        userRepository.save(user);

        sendResetEmail(user, code);
    }

    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.INVALID_RESET_CODE));

        if (user.getPasswordResetCode() == null || user.getPasswordResetExpiresAt() == null) {
            throw new GlobalException(ExceptionIdentifier.INVALID_RESET_CODE);
        }
        if (user.getPasswordResetExpiresAt().isBefore(LocalDateTime.now())) {
            clearResetCode(user);
            userRepository.save(user);
            throw new GlobalException(ExceptionIdentifier.RESET_CODE_EXPIRED);
        }
        if (!passwordEncoder.matches(request.resetCode(), user.getPasswordResetCode())) {
            throw new GlobalException(ExceptionIdentifier.INVALID_RESET_CODE);
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        clearResetCode(user);
        refreshTokenService.revokeAllFor(user);

        if (!user.isEnabled()) {
            log.info("Password reset completed for an unverified account; enabling it");
            user.setEnabled(true);
            user.setVerificationCode(null);
            user.setVerificationCodeExpiresAt(null);
        }

        userRepository.save(user);
    }

    public void changePassword(User currentUser, ChangePasswordRequest request) {
        if (!passwordEncoder.matches(request.currentPassword(), currentUser.getPassword())) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CREDENTIALS);
        }

        User user = userRepository.findById(currentUser.getId())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND));

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        clearResetCode(user);
        userRepository.save(user);
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
