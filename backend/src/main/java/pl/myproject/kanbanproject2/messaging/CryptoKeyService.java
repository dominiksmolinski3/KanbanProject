package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
import pl.myproject.kanbanproject2.user.auth.RefreshToken;
import pl.myproject.kanbanproject2.user.auth.RefreshTokenRepository;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class CryptoKeyService {

    static final int MAX_ONE_TIME_PACKAGES = 200;

    private final MessagingProperties properties;
    private final IdentityKeyRepository identities;
    private final IdentityPinRepository pins;
    private final CryptoDeviceRepository devices;
    private final KeyPackageRepository keyPackages;
    private final RefreshTokenRepository sessions;
    private final PinService pinService;
    private final IdentityKeyMapper identityMapper;
    private final IdentityBackupMapper backupMapper;
    private final CryptoDeviceMapper deviceMapper;
    private final KeyDirectory keyDirectory;
    private final Clock clock;

    @Autowired
    public CryptoKeyService(MessagingProperties properties, IdentityKeyRepository identities,
                            IdentityPinRepository pins, CryptoDeviceRepository devices,
                            KeyPackageRepository keyPackages, RefreshTokenRepository sessions, PinService pinService,
                            IdentityKeyMapper identityMapper, IdentityBackupMapper backupMapper,
                            CryptoDeviceMapper deviceMapper, KeyDirectory keyDirectory) {
        this(properties, identities, pins, devices, keyPackages, sessions, pinService, identityMapper, backupMapper,
                deviceMapper, keyDirectory, Clock.systemUTC());
    }

    CryptoKeyService(MessagingProperties properties, IdentityKeyRepository identities, IdentityPinRepository pins,
                     CryptoDeviceRepository devices, KeyPackageRepository keyPackages,
                     RefreshTokenRepository sessions, PinService pinService, IdentityKeyMapper identityMapper,
                     IdentityBackupMapper backupMapper, CryptoDeviceMapper deviceMapper, KeyDirectory keyDirectory,
                     Clock clock) {
        this.properties = properties;
        this.identities = identities;
        this.pins = pins;
        this.devices = devices;
        this.keyPackages = keyPackages;
        this.sessions = sessions;
        this.pinService = pinService;
        this.identityMapper = identityMapper;
        this.backupMapper = backupMapper;
        this.deviceMapper = deviceMapper;
        this.keyDirectory = keyDirectory;
        this.clock = clock;
    }

    @Transactional
    public CryptoStatusDto status(User caller) {
        if (!properties.enabled()) {
            return new CryptoStatusDto(false, null, null, List.of());
        }
        IdentityKeyDto identity = identities.findById(caller.getId()).map(identityMapper).orElse(null);
        return new CryptoStatusDto(true, identity, pinService.status(caller), liveDevices(caller, clock.instant()));
    }

    @Transactional
    public IdentityKeyDto createIdentity(User caller, IdentityKeyRequest request) {
        requireEnabled();
        requirePublicKey(request.publicKey());
        if (identities.existsById(caller.getId())) {
            throw new GlobalException(ExceptionIdentifier.IDENTITY_KEY_EXISTS,
                    "To konto ma już klucz tożsamości do wiadomości");
        }
        Instant now = clock.instant();
        IdentityKey saved = identities.save(
                new IdentityKey(caller, request.publicKey(), request.wrappedPrivate(), now));
        keyDirectory.identityChanged(caller, saved.getPublicKey(), now);
        log.info("Created a messaging identity key for user {}", caller.getId());
        return identityMapper.apply(saved);
    }

    @Transactional
    public IdentityKeyDto replaceIdentity(User caller, IdentityKeyRequest request) {
        requireEnabled();
        requirePublicKey(request.publicKey());
        IdentityKey identity = requireIdentity(caller);
        Instant now = clock.instant();
        List<CryptoDevice> live = devices.findLiveFor(caller);
        keyPackages.deleteAllOf(caller);
        int revoked = devices.revokeAllFor(caller, now);
        keyDirectory.devicesRevoked(live, now);
        pins.findById(caller.getId()).ifPresent(pins::delete);
        identity.replace(request.publicKey(), request.wrappedPrivate(), now);
        IdentityKey saved = identities.save(identity);
        keyDirectory.identityChanged(caller, saved.getPublicKey(), now);
        log.info("Replaced the messaging identity key for user {} and revoked {} device(s)", caller.getId(), revoked);
        return identityMapper.apply(saved);
    }

    @Transactional
    public IdentityBackupDto backup(User caller) {
        requireEnabled();
        IdentityKey identity = requireIdentity(caller);
        if (!identity.hasRecoveryKey()) {
            throw new GlobalException(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND,
                    "To konto nie ma klucza odzyskiwania");
        }
        return backupMapper.apply(identity);
    }

    @Transactional
    public IdentityKeyDto setRecoveryKey(User caller, RecoveryWrapRequest request) {
        requireEnabled();
        IdentityKey identity = requireIdentity(caller);
        if (!IdentitySignatures.verify(identity.getPublicKey(), request.signature(), IdentitySignatures.RECOVERY,
                request.wrappedPrivate())) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CRYPTO_KEY,
                    "Klucz odzyskiwania nie został podpisany kluczem tożsamości");
        }
        identity.wrapForRecovery(request.wrappedPrivate());
        return identityMapper.apply(identities.save(identity));
    }

    @Transactional
    public CryptoDeviceDto registerDevice(User caller, RegisterDeviceRequest request, String userAgent) {
        requireEnabled();
        IdentityKey identity = requireIdentity(caller);
        Instant now = clock.instant();
        UUID chain = liveChainOf(caller, request.sessionId(), now);
        if (!IdentitySignatures.isCredentialOf(caller.getId(), request.credential())
                || !IdentitySignatures.verify(identity.getPublicKey(), request.identitySignature(),
                IdentitySignatures.DEVICE, request.signatureKey(), request.credential())) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CRYPTO_KEY,
                    "Podpis urządzenia nie zgadza się z kluczem tożsamości");
        }
        requireDistinct(request.keyPackages());
        requireAtMostOneLastResort(request.keyPackages());

        List<CryptoDevice> onChain = devices.findLiveOnChain(chain);
        keyPackages.deleteAllOnChain(chain);
        int replaced = devices.revokeOnChain(chain, now);
        keyDirectory.devicesRevoked(onChain, now);
        CryptoDevice device = devices.save(new CryptoDevice(caller, chain, request.signatureKey(),
                request.credential(), request.identitySignature(), label(userAgent), now));
        store(device, request.keyPackages(), now);
        keyDirectory.deviceAdded(device, now);
        log.info("Registered messaging device {} for user {} (replaced {})", device.getId(), caller.getId(), replaced);
        return deviceMapper.apply(new LiveCryptoDevice(device, request.sessionId(),
                keyPackages.countByDeviceAndLastResortFalse(device)));
    }

    @Transactional
    public CryptoDeviceDto attachSession(User caller, Long deviceId, AttachSessionRequest request, String userAgent) {
        requireEnabled();
        Instant now = clock.instant();
        CryptoDevice device = requireLiveDevice(caller, deviceId);
        UUID chain = liveChainOf(caller, request.sessionId(), now);
        if (!chain.equals(device.getSessionChain())) {
            List<CryptoDevice> displaced = devices.findLiveFor(caller).stream()
                    .filter(other -> !other.getId().equals(deviceId) && chain.equals(other.getSessionChain()))
                    .toList();
            displaced.forEach(other -> {
                other.revokeAt(now);
                keyPackages.deleteAllOfDevice(other);
            });
            keyDirectory.devicesRevoked(displaced, now);
        }
        device.attachTo(chain, label(userAgent), now);
        devices.save(device);
        return deviceMapper.apply(new LiveCryptoDevice(device, request.sessionId(),
                keyPackages.countByDeviceAndLastResortFalse(device)));
    }

    @Transactional
    public void forgetDevice(User caller, Long deviceId) {
        requireEnabled();
        CryptoDevice device = requireLiveDevice(caller, deviceId);
        Instant now = clock.instant();
        device.revokeAt(now);
        devices.save(device);
        keyPackages.deleteAllOfDevice(device);
        keyDirectory.devicesRevoked(List.of(device), now);
        log.info("Forgot messaging device {} for user {}", deviceId, caller.getId());
    }

    @Transactional
    public KeyPackageCountDto uploadKeyPackages(User caller, Long deviceId, KeyPackagesRequest request) {
        requireEnabled();
        CryptoDevice device = requireLiveDevice(caller, deviceId);
        requireDistinct(request.keyPackages());
        requireAtMostOneLastResort(request.keyPackages());
        long oneTime = request.keyPackages().stream().filter(upload -> !upload.lastResort()).count();
        if (keyPackages.countByDeviceAndLastResortFalse(device) + oneTime > MAX_ONE_TIME_PACKAGES) {
            throw new GlobalException(ExceptionIdentifier.INVALID_KEY_PACKAGES,
                    "Urządzenie może mieć najwyżej " + MAX_ONE_TIME_PACKAGES + " pakietów kluczy");
        }
        Instant now = clock.instant();
        store(device, request.keyPackages(), now);
        device.seenAt(now);
        devices.save(device);
        return new KeyPackageCountDto(keyPackages.countByDeviceAndLastResortFalse(device));
    }

    private List<CryptoDeviceDto> liveDevices(User caller, Instant now) {
        List<CryptoDevice> live = devices.findLiveFor(caller);
        if (live.isEmpty()) {
            return List.of();
        }
        Map<UUID, Long> sessionByChain = new HashMap<>();
        List<RefreshToken> liveSessions =
                sessions.findByUserAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(caller, now);
        for (RefreshToken token : liveSessions) {
            sessionByChain.putIfAbsent(token.getChainId(), token.getId());
        }
        Map<Long, Long> available = new HashMap<>();
        for (KeyPackageRepository.DeviceKeyPackageCount count : keyPackages.countOneTimeByDevice(live)) {
            available.put(count.getDeviceId(), count.getAvailable());
        }
        return live.stream()
                .map(device -> new LiveCryptoDevice(device, sessionByChain.get(device.getSessionChain()),
                        available.getOrDefault(device.getId(), 0L)))
                .map(deviceMapper)
                .toList();
    }

    private UUID liveChainOf(User caller, Long sessionId, Instant now) {
        UUID chain = sessions.findById(sessionId)
                .filter(token -> token.getUser().getId().equals(caller.getId()))
                .map(RefreshToken::getChainId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.SESSION_NOT_FOUND,
                        "Nie znaleziono sesji"));
        if (!sessions.existsByChainIdAndRevokedAtIsNullAndExpiresAtAfter(chain, now)) {
            throw new GlobalException(ExceptionIdentifier.SESSION_NOT_FOUND, "Ta sesja już się zakończyła");
        }
        return chain;
    }

    private CryptoDevice requireLiveDevice(User caller, Long deviceId) {
        return devices.findLive(caller, deviceId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.CRYPTO_DEVICE_NOT_FOUND,
                        "Nie znaleziono urządzenia do wiadomości"));
    }

    private void store(CryptoDevice device, List<KeyPackageUpload> uploads, Instant now) {
        if (uploads.stream().anyMatch(KeyPackageUpload::lastResort)) {
            keyPackages.deleteLastResortOf(device);
        }
        keyPackages.saveAll(uploads.stream()
                .map(upload -> new KeyPackage(device, upload.ref(), upload.data(), upload.lastResort(), now))
                .toList());
    }

    private IdentityKey requireIdentity(User caller) {
        return identities.findById(caller.getId())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND,
                        "To konto nie ma jeszcze klucza tożsamości do wiadomości"));
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new GlobalException(ExceptionIdentifier.MESSAGING_UNAVAILABLE,
                    "Szyfrowane wiadomości nie są dostępne");
        }
    }

    private static String label(String userAgent) {
        return new DeviceContext(null, userAgent).userAgent();
    }

    private static void requirePublicKey(byte[] publicKey) {
        if (!IdentitySignatures.isPublicKey(publicKey)) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CRYPTO_KEY,
                    "Klucz tożsamości nie jest poprawnym kluczem Ed25519");
        }
    }

    private static void requireDistinct(List<KeyPackageUpload> uploads) {
        Set<ByteBuffer> refs = new HashSet<>();
        for (KeyPackageUpload upload : uploads) {
            if (!refs.add(ByteBuffer.wrap(upload.ref()))) {
                throw new GlobalException(ExceptionIdentifier.INVALID_KEY_PACKAGES,
                        "Ten sam pakiet kluczy wysłano dwa razy");
            }
        }
    }

    private static void requireAtMostOneLastResort(List<KeyPackageUpload> uploads) {
        if (uploads.stream().filter(KeyPackageUpload::lastResort).count() > 1) {
            throw new GlobalException(ExceptionIdentifier.INVALID_KEY_PACKAGES,
                    "Urządzenie może mieć tylko jeden zapasowy pakiet kluczy");
        }
    }
}
