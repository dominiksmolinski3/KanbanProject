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
import pl.myproject.kanbanproject2.user.EmailAddresses;
import pl.myproject.kanbanproject2.user.SupportedLocales;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.ConfirmEmailChangeRequest;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
import pl.myproject.kanbanproject2.user.auth.EmailChangeRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Objects;

@RequiredArgsConstructor
@Service
@Slf4j
public class EmailChangeService {

    static final int CODE_TTL_MINUTES = 15;
    static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;
    private final AuthenticationService authenticationService;

    @Transactional
    public void requestChange(User caller, EmailChangeRequest request) {
        User user = load(caller);
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new GlobalException(ExceptionIdentifier.WRONG_PASSWORD);
        }

        String address = EmailAddresses.normalise(request.newEmail());
        if (address.equals(user.getEmail())) {
            throw new GlobalException(ExceptionIdentifier.EMAIL_UNCHANGED);
        }
        if (address.equals(user.getPendingEmail()) && isLive(user)) {
            log.info("Email change re-requested for the address already pending; sending nothing new");
            return;
        }
        // Answered exactly like a free address, or this route would tell a caller which addresses have accounts.
        if (userRepository.findByEmail(address).isPresent()) {
            log.info("Email change requested to an address that already has an account; sending nothing");
            clearPending(user);
            userRepository.save(user);
            return;
        }

        user.setPendingEmail(address);
        user.setEmailChangeCode(String.valueOf(RANDOM.nextInt(900000) + 100000));
        user.setEmailChangeExpiresAt(now().plusMinutes(CODE_TTL_MINUTES));
        user.setEmailChangeAttempts(0);
        userRepository.save(user);
        try {
            emailService.sendEmailChangeCode(address, user.getEmailChangeCode(), CODE_TTL_MINUTES,
                    SupportedLocales.toLocale(user.getLocale()));
        } catch (EmailDeliveryException e) {
            throw new GlobalException(ExceptionIdentifier.EMAIL_SEND_FAILED, e);
        }
    }

    @Transactional(dontRollbackOn = GlobalException.class)
    public LoginResponse confirmChange(User caller, ConfirmEmailChangeRequest request, DeviceContext device) {
        User user = load(caller);
        if (user.getPendingEmail() == null || user.getEmailChangeCode() == null) {
            throw new GlobalException(ExceptionIdentifier.INVALID_VERIFICATION_CODE);
        }
        if (!isLive(user)) {
            clearPending(user);
            userRepository.save(user);
            throw new GlobalException(ExceptionIdentifier.VERIFICATION_CODE_EXPIRED);
        }
        if (!sameCode(user.getEmailChangeCode(), request.code())) {
            user.setEmailChangeAttempts(user.getEmailChangeAttempts() + 1);
            if (user.getEmailChangeAttempts() >= MAX_ATTEMPTS) {
                clearPending(user);
            }
            userRepository.save(user);
            throw new GlobalException(ExceptionIdentifier.INVALID_VERIFICATION_CODE);
        }

        String address = user.getPendingEmail();
        clearPending(user);
        if (userRepository.findByEmail(address).isPresent()) {
            userRepository.save(user);
            throw new GlobalException(ExceptionIdentifier.INVALID_VERIFICATION_CODE);
        }
        user.setEmail(address);
        User saved = userRepository.save(user);
        refreshTokenService.revokeAllFor(saved);
        return authenticationService.startSession(saved, device);
    }

    private User load(User caller) {
        if (caller == null) {
            throw new GlobalException(ExceptionIdentifier.NOT_ACCOUNT_OWNER);
        }
        return userRepository.findById(caller.getId())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND));
    }

    private boolean isLive(User user) {
        return user.getEmailChangeExpiresAt() != null && user.getEmailChangeExpiresAt().isAfter(now());
    }

    private static boolean sameCode(String expected, String given) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                Objects.requireNonNullElse(given, "").getBytes(StandardCharsets.UTF_8));
    }

    private static void clearPending(User user) {
        user.setPendingEmail(null);
        user.setEmailChangeCode(null);
        user.setEmailChangeExpiresAt(null);
        user.setEmailChangeAttempts(0);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
