package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;
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
    private final CryptoDeviceRepository devices;
    private final KeyPackageRepository keyPackages;
    private final RefreshTokenRepository sessions;
    private final IdentityKeyMapper identityMapper;
    private final IdentityBackupMapper backupMapper;
    private final CryptoDeviceMapper deviceMapper;
    private final Clock clock;

    @Autowired
    public CryptoKeyService(MessagingProperties properties, IdentityKeyRepository identities,
                            CryptoDeviceRepository devices, KeyPackageRepository keyPackages,
                            RefreshTokenRepository sessions, IdentityKeyMapper identityMapper,
                            IdentityBackupMapper backupMapper, CryptoDeviceMapper deviceMapper) {
        this(properties, identities, devices, keyPackages, sessions, identityMapper, backupMapper, deviceMapper,
                Clock.systemUTC());
    }

    CryptoKeyService(MessagingProperties properties, IdentityKeyRepository identities,
                     CryptoDeviceRepository devices, KeyPackageRepository keyPackages,
                     RefreshTokenRepository sessions, IdentityKeyMapper identityMapper,
                     IdentityBackupMapper backupMapper, CryptoDeviceMapper deviceMapper, Clock clock) {
        this.properties = properties;
        this.identities = identities;
        this.devices = devices;
        this.keyPackages = keyPackages;
        this.sessions = sessions;
        this.identityMapper = identityMapper;
        this.backupMapper = backupMapper;
        this.deviceMapper = deviceMapper;
        this.clock = clock;
    }

    @Transactional
    public CryptoStatusDto status(User caller) {
        if (!properties.enabled()) {
            return new CryptoStatusDto(false, null, List.of());
        }
        Instant now = clock.instant();
        IdentityKeyDto identity = identities.findById(caller.getId()).map(identityMapper).orElse(null);
        return new CryptoStatusDto(true, identity, liveDevices(caller, now));
    }

    @Transactional
    public IdentityKeyDto createIdentity(User caller, IdentityKeyRequest request) {
        requireEnabled();
        requirePublicKey(request.publicKey());
        if (identities.existsById(caller.getId())) {
            throw new GlobalException(ExceptionIdentifier.IDENTITY_KEY_EXISTS,
                    "To konto ma już klucz tożsamości do wiadomości");
        }
        IdentityKey saved = identities.save(
                new IdentityKey(caller, request.publicKey(), request.wrappedPrivate(), clock.instant()));
        log.info("Created a messaging identity key for user {}", caller.getId());
        return identityMapper.apply(saved);
    }

    @Transactional
    public IdentityKeyDto replaceIdentity(User caller, IdentityKeyRequest request) {
        requireEnabled();
        requirePublicKey(request.publicKey());
        IdentityKey identity = requireIdentity(caller);
        Instant now = clock.instant();
        keyPackages.deleteAllOf(caller);
        int revoked = devices.revokeAllFor(caller, now);
        identity.replace(request.publicKey(), request.wrappedPrivate(), now);
        IdentityKey saved = identities.save(identity);
        log.info("Replaced the messaging identity key for user {} and revoked {} device(s)", caller.getId(), revoked);
        return identityMapper.apply(saved);
    }

    @Transactional
    public IdentityBackupDto backup(User caller) {
        requireEnabled();
        return backupMapper.apply(requireIdentity(caller));
    }

    @Transactional
    public CryptoDeviceDto registerDevice(User caller, RegisterDeviceRequest request) {
        requireEnabled();
        IdentityKey identity = requireIdentity(caller);
        Instant now = clock.instant();
        UUID chain = liveChainOf(caller, request.sessionId(), now);
        if (!DeviceSignatures.isCredentialOf(caller.getId(), request.credential())
                || !DeviceSignatures.verify(identity.getPublicKey(), request.signatureKey(), request.credential(),
                request.identitySignature())) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CRYPTO_KEY,
                    "Podpis urządzenia nie zgadza się z kluczem tożsamości");
        }
        requireDistinct(request.keyPackages());
        requireAtMostOneLastResort(request.keyPackages());

        keyPackages.deleteAllOnChain(chain);
        int replaced = devices.revokeOnChain(chain, now);
        CryptoDevice device = devices.save(new CryptoDevice(caller, chain, request.signatureKey(),
                request.credential(), request.identitySignature(), now));
        store(device, request.keyPackages(), now);
        log.info("Registered messaging device {} for user {} (replaced {})", device.getId(), caller.getId(), replaced);
        return deviceMapper.apply(new LiveCryptoDevice(device, request.sessionId(),
                keyPackages.countByDeviceAndLastResortFalse(device)));
    }

    @Transactional
    public KeyPackageCountDto uploadKeyPackages(User caller, Long deviceId, KeyPackagesRequest request) {
        requireEnabled();
        CryptoDevice device = devices.findLive(caller, deviceId, clock.instant())
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.CRYPTO_DEVICE_NOT_FOUND,
                        "Nie znaleziono urządzenia do wiadomości"));
        requireDistinct(request.keyPackages());
        requireAtMostOneLastResort(request.keyPackages());
        long oneTime = request.keyPackages().stream().filter(upload -> !upload.lastResort()).count();
        if (keyPackages.countByDeviceAndLastResortFalse(device) + oneTime > MAX_ONE_TIME_PACKAGES) {
            throw new GlobalException(ExceptionIdentifier.INVALID_KEY_PACKAGES,
                    "Urządzenie może mieć najwyżej " + MAX_ONE_TIME_PACKAGES + " pakietów kluczy");
        }
        store(device, request.keyPackages(), clock.instant());
        return new KeyPackageCountDto(keyPackages.countByDeviceAndLastResortFalse(device));
    }

    private List<CryptoDeviceDto> liveDevices(User caller, Instant now) {
        List<CryptoDevice> live = devices.findLiveFor(caller, now);
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

    private static void requirePublicKey(byte[] publicKey) {
        if (!DeviceSignatures.isPublicKey(publicKey)) {
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
