package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;

@Slf4j
@Service
public class PinService {

    static final int VERIFIER_BYTES = 32;
    static final int MAX_ATTEMPTS = 10;

    private final MessagingProperties properties;
    private final PinEvaluator evaluator;
    private final IdentityKeyRepository identities;
    private final IdentityPinRepository pins;
    private final Clock clock;

    @Autowired
    public PinService(MessagingProperties properties, PinEvaluator evaluator, IdentityKeyRepository identities,
                      IdentityPinRepository pins) {
        this(properties, evaluator, identities, pins, Clock.systemUTC());
    }

    PinService(MessagingProperties properties, PinEvaluator evaluator, IdentityKeyRepository identities,
               IdentityPinRepository pins, Clock clock) {
        this.properties = properties;
        this.evaluator = evaluator;
        this.identities = identities;
        this.pins = pins;
        this.clock = clock;
    }

    public PinStatusDto status(User caller) {
        return pins.findById(caller.getId())
                .map(pin -> new PinStatusDto(evaluator.isConfigured(), true,
                        Math.max(0, MAX_ATTEMPTS - pin.getFailedAttempts())))
                .orElseGet(() -> new PinStatusDto(evaluator.isConfigured(), false, MAX_ATTEMPTS));
    }

    // Every evaluation of a set PIN is charged as a guess; only a correct unlock refunds them.
    @Transactional
    public PinEvaluationDto evaluate(User caller, PinEvaluationRequest request) {
        requireAvailable();
        if (pins.existsById(caller.getId()) && pins.spendAttempt(caller.getId(), MAX_ATTEMPTS) == 0) {
            throw locked();
        }
        byte[] evaluated = evaluator.evaluate(request.blinded())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.INVALID_CRYPTO_KEY,
                        "Zaślepiony PIN nie jest punktem krzywej P-256"));
        return new PinEvaluationDto(evaluated);
    }

    @Transactional
    public PinStatusDto setPin(User caller, PinRequest request) {
        requireAvailable();
        IdentityKey identity = identities.findById(caller.getId())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND,
                        "To konto nie ma jeszcze klucza tożsamości do wiadomości"));
        if (!IdentitySignatures.verify(identity.getPublicKey(), request.signature(), IdentitySignatures.PIN,
                request.wrappedPrivate(), request.verifier())) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CRYPTO_KEY,
                    "PIN nie został podpisany kluczem tożsamości");
        }
        IdentityPin pin = pins.findById(caller.getId())
                .map(existing -> {
                    existing.replace(request.wrappedPrivate(), request.verifier(), clock.instant());
                    return existing;
                })
                .orElseGet(() -> new IdentityPin(caller, request.wrappedPrivate(), request.verifier(), clock.instant()));
        pins.save(pin);
        log.info("Set a messaging PIN for user {}", caller.getId());
        return new PinStatusDto(true, true, MAX_ATTEMPTS);
    }

    @Transactional
    public IdentityBackupDto unlock(User caller, PinUnlockRequest request) {
        requireAvailable();
        IdentityPin pin = pins.findById(caller.getId())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.PIN_NOT_SET,
                        "To konto nie ma ustawionego PIN-u"));
        if (!MessageDigest.isEqual(pin.getVerifier(), sha256(request.proof()))) {
            int left = MAX_ATTEMPTS - pin.getFailedAttempts();
            if (left <= 0) {
                throw locked();
            }
            throw new GlobalException(ExceptionIdentifier.PIN_INCORRECT,
                    "Nieprawidłowy PIN, pozostało prób: " + left);
        }
        pin.unlocked();
        pins.save(pin);
        IdentityKey identity = identities.findById(caller.getId())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND,
                        "To konto nie ma jeszcze klucza tożsamości do wiadomości"));
        return new IdentityBackupDto(identity.getPublicKey(), pin.getWrappedPrivate());
    }

    private void requireAvailable() {
        if (!properties.enabled() || !evaluator.isConfigured()) {
            throw new GlobalException(ExceptionIdentifier.MESSAGING_UNAVAILABLE, "PIN do wiadomości nie jest dostępny");
        }
    }

    private static GlobalException locked() {
        return new GlobalException(ExceptionIdentifier.PIN_LOCKED,
                "PIN jest zablokowany po zbyt wielu próbach; użyj klucza odzyskiwania");
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
