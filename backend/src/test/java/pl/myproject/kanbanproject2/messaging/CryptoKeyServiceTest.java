package pl.myproject.kanbanproject2.messaging;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
import pl.myproject.kanbanproject2.user.auth.RefreshToken;
import pl.myproject.kanbanproject2.user.auth.RefreshTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CryptoKeyServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final String AGENT = "Mozilla/5.0 (X11; Linux x86_64)";

    private IdentityKeyRepository identities;
    private IdentityPinRepository pins;
    private CryptoDeviceRepository devices;
    private KeyPackageRepository keyPackages;
    private RefreshTokenRepository sessions;
    private CryptoKeyService service;
    private User caller;
    private Ed25519TestKeys identityKeys;

    private CryptoKeyService serviceWith(boolean enabled) {
        var properties = new MessagingProperties(enabled, "test-pin-secret");
        var pinService = new PinService(properties, new PinEvaluator(properties), identities, pins,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new CryptoKeyService(properties, identities, pins, devices, keyPackages, sessions, pinService,
                new IdentityKeyMapper(), new IdentityBackupMapper(), new CryptoDeviceMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        identities = mock(IdentityKeyRepository.class);
        pins = mock(IdentityPinRepository.class);
        devices = mock(CryptoDeviceRepository.class);
        keyPackages = mock(KeyPackageRepository.class);
        sessions = mock(RefreshTokenRepository.class);
        service = serviceWith(true);
        caller = new User("someone", "someone@example.test", "hashed");
        caller.setId(7);
        identityKeys = Ed25519TestKeys.generate();
        when(identities.save(any(IdentityKey.class))).thenAnswer(call -> call.getArgument(0));
        when(devices.save(any(CryptoDevice.class))).thenAnswer(call -> {
            CryptoDevice device = call.getArgument(0);
            if (device.getId() == null) {
                ReflectionTestUtils.setField(device, "id", 31L);
            }
            return device;
        });
    }

    private RefreshToken session(Long id, User owner, UUID chain) {
        RefreshToken token = new RefreshToken("h" + id, owner, NOW, NOW.plusSeconds(3600), NOW.plusSeconds(7200), NOW,
                chain, DeviceContext.unknown());
        ReflectionTestUtils.setField(token, "id", id);
        return token;
    }

    private IdentityKey storedIdentity(byte[] recoveryWrap) {
        return new IdentityKey(caller, identityKeys.publicKey(), recoveryWrap, NOW.minusSeconds(60));
    }

    private CryptoDevice device(Long id, UUID chain) {
        CryptoDevice device = new CryptoDevice(caller, chain, new byte[32], new byte[4], new byte[64], "Old agent",
                NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(device, "id", id);
        return device;
    }

    private static KeyPackageUpload upload(int ref, boolean lastResort) {
        return new KeyPackageUpload(new byte[]{(byte) ref}, new byte[]{1, 2, 3}, lastResort);
    }

    private void liveSession(Long id, UUID chain) {
        when(sessions.findById(id)).thenReturn(Optional.of(session(id, caller, chain)));
        when(sessions.existsByChainIdAndRevokedAtIsNullAndExpiresAtAfter(chain, NOW)).thenReturn(true);
    }

    @Nested
    class WhenTheFeatureIsOff {

        @BeforeEach
        void off() {
            service = serviceWith(false);
        }

        @Test
        @DisplayName("the status says so without reading anything")
        void statusSaysOff() {
            assertThat(service.status(caller)).isEqualTo(new CryptoStatusDto(false, null, null, List.of()));
            verifyNoInteractions(identities, devices, keyPackages, sessions, pins);
        }

        @Test
        @DisplayName("every other call answers as if the routes did not exist")
        void everythingElseIsNotFound() {
            var identity = new IdentityKeyRequest(identityKeys.publicKey(), null);
            List<Runnable> calls = List.of(
                    () -> service.createIdentity(caller, identity),
                    () -> service.replaceIdentity(caller, identity),
                    () -> service.backup(caller),
                    () -> service.setRecoveryKey(caller, new RecoveryWrapRequest(new byte[1], new byte[64])),
                    () -> service.registerDevice(caller, new RegisterDeviceRequest(1L, new byte[32], new byte[4],
                            new byte[64], List.of()), AGENT),
                    () -> service.attachSession(caller, 1L, new AttachSessionRequest(1L), AGENT),
                    () -> service.forgetDevice(caller, 1L),
                    () -> service.uploadKeyPackages(caller, 1L, new KeyPackagesRequest(List.of(upload(1, false)))));

            for (Runnable call : calls) {
                assertThatThrownBy(call::run).isInstanceOf(GlobalException.class)
                        .extracting("identifier").isEqualTo(ExceptionIdentifier.MESSAGING_UNAVAILABLE);
            }
            verifyNoInteractions(identities, devices, keyPackages, sessions);
        }
    }

    @Nested
    class Status {

        @Test
        @DisplayName("lists every live device, signed in or not, with its session, label and spare packages")
        void listsLiveDevices() {
            UUID chain = UUID.randomUUID();
            CryptoDevice signedIn = device(3L, chain);
            CryptoDevice signedOut = device(4L, UUID.randomUUID());
            when(identities.findById(7)).thenReturn(Optional.of(storedIdentity(null)));
            when(pins.findById(7)).thenReturn(Optional.empty());
            when(devices.findLiveFor(caller)).thenReturn(List.of(signedIn, signedOut));
            when(sessions.findByUserAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(caller, NOW))
                    .thenReturn(List.of(session(12L, caller, chain)));
            when(keyPackages.countOneTimeByDevice(anyList())).thenReturn(List.of(count(3L, 97)));

            CryptoStatusDto status = service.status(caller);

            assertThat(status.identity().recoveryKey()).isFalse();
            assertThat(status.pin()).isEqualTo(new PinStatusDto(true, false, 10));
            assertThat(status.devices()).extracting(CryptoDeviceDto::id, CryptoDeviceDto::sessionId,
                            CryptoDeviceDto::label, CryptoDeviceDto::keyPackages)
                    .containsExactly(Tuple.tuple(3L, 12L, "Old agent", 97L), Tuple.tuple(4L, null, "Old agent", 0L));
        }

        @Test
        @DisplayName("an account with no identity and no devices reads as not set up")
        void notSetUp() {
            when(identities.findById(7)).thenReturn(Optional.empty());
            when(pins.findById(7)).thenReturn(Optional.empty());
            when(devices.findLiveFor(caller)).thenReturn(List.of());

            CryptoStatusDto status = service.status(caller);

            assertThat(status.identity()).isNull();
            assertThat(status.devices()).isEmpty();
            verifyNoInteractions(keyPackages, sessions);
        }

        private KeyPackageRepository.DeviceKeyPackageCount count(Long deviceId, long available) {
            return new KeyPackageRepository.DeviceKeyPackageCount() {
                @Override
                public Long getDeviceId() {
                    return deviceId;
                }

                @Override
                public long getAvailable() {
                    return available;
                }
            };
        }
    }

    @Nested
    class Identity {

        @Test
        @DisplayName("is created once, with or without a recovery key")
        void createdOnce() {
            when(identities.existsById(7)).thenReturn(false);

            IdentityKeyDto withoutRecovery = service.createIdentity(caller,
                    new IdentityKeyRequest(identityKeys.publicKey(), null));
            IdentityKeyDto withRecovery = service.createIdentity(caller,
                    new IdentityKeyRequest(identityKeys.publicKey(), new byte[]{1}));

            assertThat(withoutRecovery.recoveryKey()).isFalse();
            assertThat(withRecovery.recoveryKey()).isTrue();
            assertThat(withRecovery.createdAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a second identity is refused rather than quietly replacing the first")
        void secondIsRefused() {
            when(identities.existsById(7)).thenReturn(true);

            assertThatThrownBy(() -> service.createIdentity(caller,
                    new IdentityKeyRequest(identityKeys.publicKey(), null)))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.IDENTITY_KEY_EXISTS);
            verify(identities, never()).save(any());
        }

        @Test
        @DisplayName("a public key that is not an Ed25519 point is refused")
        void malformedKeyIsRefused() {
            assertThatThrownBy(() -> service.createIdentity(caller, new IdentityKeyRequest(new byte[31], null)))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_CRYPTO_KEY);
        }

        @Test
        @DisplayName("starting over replaces the key, revokes every device and drops the PIN")
        void replacingRevokesEverything() {
            IdentityKey stored = storedIdentity(new byte[]{9});
            IdentityPin pin = new IdentityPin(caller, new byte[1], new byte[32], NOW);
            when(identities.findById(7)).thenReturn(Optional.of(stored));
            when(pins.findById(7)).thenReturn(Optional.of(pin));
            byte[] newKey = Ed25519TestKeys.generate().publicKey();

            IdentityKeyDto replaced = service.replaceIdentity(caller, new IdentityKeyRequest(newKey, null));

            assertThat(replaced.publicKey()).isEqualTo(newKey);
            assertThat(replaced.recoveryKey()).isFalse();
            verify(keyPackages).deleteAllOf(caller);
            verify(devices).revokeAllFor(caller, NOW);
            verify(pins).delete(pin);
        }

        @Test
        @DisplayName("there is nothing to start over from, or to back up, before an identity exists")
        void nothingToReplaceOrBackUp() {
            when(identities.findById(7)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.replaceIdentity(caller,
                    new IdentityKeyRequest(identityKeys.publicKey(), null)))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND);
            assertThatThrownBy(() -> service.backup(caller))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND);
            verify(devices, never()).revokeAllFor(any(), any());
        }

        @Test
        @DisplayName("the backup hands back the recovery-key wrap, and there is none when no recovery key was made")
        void backup() {
            when(identities.findById(7)).thenReturn(Optional.of(storedIdentity(new byte[]{9, 9})));
            assertThat(service.backup(caller).wrappedPrivate()).containsExactly(9, 9);

            when(identities.findById(7)).thenReturn(Optional.of(storedIdentity(null)));
            assertThatThrownBy(() -> service.backup(caller))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND);
        }

        @Test
        @DisplayName("a recovery key can be added later, only with the identity's signature over it")
        void addingARecoveryKeyLater() {
            IdentityKey stored = storedIdentity(null);
            when(identities.findById(7)).thenReturn(Optional.of(stored));
            byte[] wrapped = {4, 4};

            assertThatThrownBy(() -> service.setRecoveryKey(caller,
                    new RecoveryWrapRequest(wrapped, Ed25519TestKeys.generate().signRecovery(wrapped))))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_CRYPTO_KEY);
            assertThat(stored.hasRecoveryKey()).isFalse();

            IdentityKeyDto updated = service.setRecoveryKey(caller,
                    new RecoveryWrapRequest(wrapped, identityKeys.signRecovery(wrapped)));

            assertThat(updated.recoveryKey()).isTrue();
            assertThat(stored.getWrappedPrivate()).containsExactly(4, 4);
        }
    }

    @Nested
    class RegisteringADevice {

        private final UUID chain = UUID.randomUUID();
        private final byte[] deviceKey = Ed25519TestKeys.generate().publicKey();
        private final byte[] credential = Ed25519TestKeys.credential(7);

        @BeforeEach
        void identityAndSession() {
            when(identities.findById(7)).thenReturn(Optional.of(storedIdentity(null)));
            liveSession(12L, chain);
        }

        private RegisterDeviceRequest request(byte[] signature, List<KeyPackageUpload> uploads) {
            return new RegisterDeviceRequest(12L, deviceKey, credential, signature, uploads);
        }

        @Test
        @DisplayName("attaches the device to the caller's sign-in, labels it, replaces any earlier device there")
        @SuppressWarnings("unchecked")
        void registers() {
            when(keyPackages.countByDeviceAndLastResortFalse(any())).thenReturn(1L);

            CryptoDeviceDto registered = service.registerDevice(caller,
                    request(identityKeys.signDevice(deviceKey, credential), List.of(upload(1, false), upload(2, true))),
                    AGENT);

            assertThat(registered).extracting(CryptoDeviceDto::id, CryptoDeviceDto::sessionId, CryptoDeviceDto::label,
                    CryptoDeviceDto::lastSeenAt).containsExactly(31L, 12L, AGENT, NOW);
            var saved = ArgumentCaptor.forClass(CryptoDevice.class);
            verify(devices).save(saved.capture());
            assertThat(saved.getValue().getSessionChain()).isEqualTo(chain);
            verify(keyPackages).deleteAllOnChain(chain);
            verify(devices).revokeOnChain(chain, NOW);
            verify(keyPackages).deleteLastResortOf(saved.getValue());
            var stored = ArgumentCaptor.forClass(List.class);
            verify(keyPackages).saveAll(stored.capture());
            assertThat((List<KeyPackage>) stored.getValue()).extracting(KeyPackage::isLastResort)
                    .containsExactly(false, true);
        }

        @Test
        @DisplayName("a signature the identity key did not make is refused, and nothing is replaced")
        void forgedSignatureIsRefused() {
            byte[] forged = Ed25519TestKeys.generate().signDevice(deviceKey, credential);

            assertThatThrownBy(() -> service.registerDevice(caller, request(forged, List.of()), AGENT))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_CRYPTO_KEY);
            verify(devices, never()).revokeOnChain(any(), any());
            verify(devices, never()).save(any());
        }

        @Test
        @DisplayName("a credential naming another account is refused even when signed")
        void someoneElsesCredentialIsRefused() {
            byte[] theirs = Ed25519TestKeys.credential(8);
            var request = new RegisterDeviceRequest(12L, deviceKey, theirs, identityKeys.signDevice(deviceKey, theirs),
                    List.of());

            assertThatThrownBy(() -> service.registerDevice(caller, request, AGENT))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_CRYPTO_KEY);
        }

        @Test
        @DisplayName("another account's session, an unknown one or one that has ended cannot carry a device")
        void sessionMustBeTheCallersAndLive() {
            byte[] signature = identityKeys.signDevice(deviceKey, credential);
            User other = new User("other", "other@example.test", "hashed");
            other.setId(8);
            when(sessions.findById(13L)).thenReturn(Optional.of(session(13L, other, UUID.randomUUID())));
            when(sessions.findById(14L)).thenReturn(Optional.empty());

            for (Long sessionId : List.of(13L, 14L)) {
                var request = new RegisterDeviceRequest(sessionId, deviceKey, credential, signature, List.of());
                assertThatThrownBy(() -> service.registerDevice(caller, request, AGENT))
                        .extracting("identifier").isEqualTo(ExceptionIdentifier.SESSION_NOT_FOUND);
            }

            when(sessions.existsByChainIdAndRevokedAtIsNullAndExpiresAtAfter(chain, NOW)).thenReturn(false);
            assertThatThrownBy(() -> service.registerDevice(caller, request(signature, List.of()), AGENT))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.SESSION_NOT_FOUND);
            verify(devices, never()).save(any());
        }

        @Test
        @DisplayName("needs an identity to check the signature against")
        void needsAnIdentity() {
            when(identities.findById(7)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.registerDevice(caller, request(new byte[64], List.of()), AGENT))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND);
        }

        @Test
        @DisplayName("the same key package twice, or two last-resort packages, are refused before anything changes")
        void refusesMalformedUploads() {
            byte[] signature = identityKeys.signDevice(deviceKey, credential);

            assertThatThrownBy(() -> service.registerDevice(caller,
                    request(signature, List.of(upload(1, false), upload(1, false))), AGENT))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_KEY_PACKAGES);
            assertThatThrownBy(() -> service.registerDevice(caller,
                    request(signature, List.of(upload(1, true), upload(2, true))), AGENT))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_KEY_PACKAGES);
            verify(devices, never()).revokeOnChain(any(), any());
        }
    }

    @Nested
    class AttachingAndForgetting {

        private final UUID oldChain = UUID.randomUUID();
        private final UUID newChain = UUID.randomUUID();

        @Test
        @DisplayName("a browser signing in again moves its device onto the new sign-in and is seen now")
        void attaches() {
            CryptoDevice device = device(3L, oldChain);
            CryptoDevice squatter = device(5L, newChain);
            when(devices.findLive(caller, 3L)).thenReturn(Optional.of(device));
            when(devices.findLiveFor(caller)).thenReturn(List.of(device, squatter));
            liveSession(20L, newChain);

            CryptoDeviceDto attached = service.attachSession(caller, 3L, new AttachSessionRequest(20L), AGENT);

            assertThat(attached.sessionId()).isEqualTo(20L);
            assertThat(device.getSessionChain()).isEqualTo(newChain);
            assertThat(device.getLabel()).isEqualTo(AGENT);
            assertThat(device.getLastSeenAt()).isEqualTo(NOW);
            assertThat(squatter.getRevokedAt()).as("one sign-in holds one device").isEqualTo(NOW);
        }

        @Test
        @DisplayName("attaching to the sign-in it already has only refreshes when it was seen")
        void reattachingIsQuiet() {
            CryptoDevice device = device(3L, newChain);
            when(devices.findLive(caller, 3L)).thenReturn(Optional.of(device));
            liveSession(20L, newChain);

            service.attachSession(caller, 3L, new AttachSessionRequest(20L), null);

            assertThat(device.getLabel()).isEqualTo("Old agent");
            verify(devices, never()).findLiveFor(any());
        }

        @Test
        @DisplayName("a device that is not the caller's, or was revoked, answers 404")
        void unknownDevice() {
            when(devices.findLive(eq(caller), eq(9L))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.attachSession(caller, 9L, new AttachSessionRequest(20L), AGENT))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.CRYPTO_DEVICE_NOT_FOUND);
            assertThatThrownBy(() -> service.forgetDevice(caller, 9L))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.CRYPTO_DEVICE_NOT_FOUND);
        }

        @Test
        @DisplayName("forgetting a browser revokes its device and drops its key packages")
        void forgets() {
            CryptoDevice device = device(3L, oldChain);
            when(devices.findLive(caller, 3L)).thenReturn(Optional.of(device));

            service.forgetDevice(caller, 3L);

            assertThat(device.getRevokedAt()).isEqualTo(NOW);
            verify(keyPackages).deleteAllOfDevice(device);
        }
    }

    @Nested
    class UploadingKeyPackages {

        private CryptoDevice device;

        @BeforeEach
        void liveDevice() {
            device = device(3L, UUID.randomUUID());
            when(devices.findLive(caller, 3L)).thenReturn(Optional.of(device));
        }

        @Test
        @DisplayName("adds them to a live device of the caller's, marks it seen and answers with the spare count")
        void adds() {
            when(keyPackages.countByDeviceAndLastResortFalse(device)).thenReturn(10L, 12L);

            KeyPackageCountDto count = service.uploadKeyPackages(caller, 3L,
                    new KeyPackagesRequest(List.of(upload(1, false), upload(2, false))));

            assertThat(count.available()).isEqualTo(12L);
            assertThat(device.getLastSeenAt()).isEqualTo(NOW);
            verify(keyPackages, never()).deleteLastResortOf(any());
        }

        @Test
        @DisplayName("a new last-resort package replaces the old one")
        void replacesTheLastResort() {
            when(keyPackages.countByDeviceAndLastResortFalse(device)).thenReturn(0L);

            service.uploadKeyPackages(caller, 3L, new KeyPackagesRequest(List.of(upload(1, true))));

            verify(keyPackages).deleteLastResortOf(device);
        }

        @Test
        @DisplayName("stops a device hoarding more than the cap")
        void capsTheTotal() {
            when(keyPackages.countByDeviceAndLastResortFalse(device)).thenReturn(199L);

            assertThatThrownBy(() -> service.uploadKeyPackages(caller, 3L,
                    new KeyPackagesRequest(List.of(upload(1, false), upload(2, false)))))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_KEY_PACKAGES);
            verify(keyPackages, never()).saveAll(anyList());
        }
    }
}
