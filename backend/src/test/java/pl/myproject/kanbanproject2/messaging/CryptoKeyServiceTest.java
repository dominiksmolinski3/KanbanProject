package pl.myproject.kanbanproject2.messaging;

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
import java.util.ArrayList;
import java.util.Collections;
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

    private IdentityKeyRepository identities;
    private CryptoDeviceRepository devices;
    private KeyPackageRepository keyPackages;
    private RefreshTokenRepository sessions;
    private CryptoKeyService service;
    private User caller;
    private Ed25519TestKeys identityKeys;

    private CryptoKeyService serviceWith(boolean enabled) {
        return new CryptoKeyService(new MessagingProperties(enabled), identities, devices, keyPackages, sessions,
                new IdentityKeyMapper(), new IdentityBackupMapper(), new CryptoDeviceMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        identities = mock(IdentityKeyRepository.class);
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
            ReflectionTestUtils.setField(device, "id", 31L);
            return device;
        });
    }

    private RefreshToken session(Long id, User owner, UUID chain) {
        RefreshToken token = new RefreshToken("h" + id, owner, NOW, NOW.plusSeconds(3600), NOW.plusSeconds(7200), NOW,
                chain, DeviceContext.unknown());
        ReflectionTestUtils.setField(token, "id", id);
        return token;
    }

    private IdentityKey storedIdentity() {
        return new IdentityKey(caller, identityKeys.publicKey(), new byte[]{9, 9}, NOW.minusSeconds(60));
    }

    private static KeyPackageUpload upload(int ref, boolean lastResort) {
        return new KeyPackageUpload(new byte[]{(byte) ref}, new byte[]{1, 2, 3}, lastResort);
    }

    private IdentityKeyRequest identityRequest() {
        return new IdentityKeyRequest(identityKeys.publicKey(), new byte[]{1, 2, 3});
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
            assertThat(service.status(caller)).isEqualTo(new CryptoStatusDto(false, null, List.of()));
            verifyNoInteractions(identities, devices, keyPackages, sessions);
        }

        @Test
        @DisplayName("every other call answers as if the routes did not exist")
        void everythingElseIsNotFound() {
            List<Runnable> calls = List.of(
                    () -> service.createIdentity(caller, identityRequest()),
                    () -> service.replaceIdentity(caller, identityRequest()),
                    () -> service.backup(caller),
                    () -> service.registerDevice(caller, new RegisterDeviceRequest(1L, new byte[32], new byte[4],
                            new byte[64], List.of())),
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
        @DisplayName("lists only live devices, each with the session it lives on and its spare key packages")
        void listsLiveDevices() {
            UUID chain = UUID.randomUUID();
            CryptoDevice device = new CryptoDevice(caller, chain, new byte[32], new byte[4], new byte[64], NOW);
            ReflectionTestUtils.setField(device, "id", 3L);
            CryptoDevice orphan = new CryptoDevice(caller, UUID.randomUUID(), new byte[32], new byte[4], new byte[64], NOW);
            ReflectionTestUtils.setField(orphan, "id", 4L);
            when(identities.findById(7)).thenReturn(Optional.of(storedIdentity()));
            when(devices.findLiveFor(caller, NOW)).thenReturn(List.of(device, orphan));
            when(sessions.findByUserAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(caller, NOW))
                    .thenReturn(List.of(session(12L, caller, chain)));
            when(keyPackages.countOneTimeByDevice(anyList())).thenReturn(List.of(count(3L, 97)));

            CryptoStatusDto status = service.status(caller);

            assertThat(status.enabled()).isTrue();
            assertThat(status.identity().publicKey()).isEqualTo(identityKeys.publicKey());
            assertThat(status.devices()).extracting(CryptoDeviceDto::id, CryptoDeviceDto::sessionId,
                            CryptoDeviceDto::keyPackages)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(3L, 12L, 97L),
                            org.assertj.core.groups.Tuple.tuple(4L, null, 0L));
        }

        @Test
        @DisplayName("an account with no identity and no devices reads as not set up")
        void notSetUp() {
            when(identities.findById(7)).thenReturn(Optional.empty());
            when(devices.findLiveFor(caller, NOW)).thenReturn(List.of());

            assertThat(service.status(caller)).isEqualTo(new CryptoStatusDto(true, null, List.of()));
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
        @DisplayName("is created once, from a valid Ed25519 public key")
        void createdOnce() {
            when(identities.existsById(7)).thenReturn(false);

            IdentityKeyDto created = service.createIdentity(caller, identityRequest());

            assertThat(created.publicKey()).isEqualTo(identityKeys.publicKey());
            assertThat(created.createdAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a second identity is refused rather than quietly replacing the first")
        void secondIsRefused() {
            when(identities.existsById(7)).thenReturn(true);

            assertThatThrownBy(() -> service.createIdentity(caller, identityRequest()))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.IDENTITY_KEY_EXISTS);
            verify(identities, never()).save(any());
        }

        @Test
        @DisplayName("a public key that is not an Ed25519 point is refused")
        void malformedKeyIsRefused() {
            assertThatThrownBy(() -> service.createIdentity(caller, new IdentityKeyRequest(new byte[31], new byte[1])))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_CRYPTO_KEY);
        }

        @Test
        @DisplayName("starting over replaces the key and revokes every device, with their key packages")
        void replacingRevokesEveryDevice() {
            IdentityKey stored = storedIdentity();
            when(identities.findById(7)).thenReturn(Optional.of(stored));
            byte[] newKey = Ed25519TestKeys.generate().publicKey();

            IdentityKeyDto replaced = service.replaceIdentity(caller, new IdentityKeyRequest(newKey, new byte[]{4}));

            assertThat(replaced.publicKey()).isEqualTo(newKey);
            assertThat(stored.getWrappedPrivate()).containsExactly(4);
            verify(keyPackages).deleteAllOf(caller);
            verify(devices).revokeAllFor(caller, NOW);
        }

        @Test
        @DisplayName("there is nothing to start over from, or to back up, before an identity exists")
        void nothingToReplaceOrBackUp() {
            when(identities.findById(7)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.replaceIdentity(caller, identityRequest()))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND);
            assertThatThrownBy(() -> service.backup(caller))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND);
            verify(devices, never()).revokeAllFor(any(), any());
        }

        @Test
        @DisplayName("the backup hands back the wrapped private half for the recovery key to open")
        void backupReturnsTheWrappedKey() {
            when(identities.findById(7)).thenReturn(Optional.of(storedIdentity()));

            IdentityBackupDto backup = service.backup(caller);

            assertThat(backup.publicKey()).isEqualTo(identityKeys.publicKey());
            assertThat(backup.wrappedPrivate()).containsExactly(9, 9);
        }
    }

    @Nested
    class RegisteringADevice {

        private final UUID chain = UUID.randomUUID();
        private final byte[] deviceKey = Ed25519TestKeys.generate().publicKey();
        private final byte[] credential = Ed25519TestKeys.credential(7);

        @BeforeEach
        void liveSession() {
            when(identities.findById(7)).thenReturn(Optional.of(storedIdentity()));
            when(sessions.findById(12L)).thenReturn(Optional.of(session(12L, caller, chain)));
            when(sessions.existsByChainIdAndRevokedAtIsNullAndExpiresAtAfter(chain, NOW)).thenReturn(true);
        }

        private RegisterDeviceRequest request(byte[] signature, List<KeyPackageUpload> uploads) {
            return new RegisterDeviceRequest(12L, deviceKey, credential, signature, uploads);
        }

        @Test
        @DisplayName("binds the device to the caller's sign-in, replaces any earlier device there and stores its packages")
        @SuppressWarnings("unchecked")
        void registers() {
            when(keyPackages.countByDeviceAndLastResortFalse(any())).thenReturn(1L);

            CryptoDeviceDto registered = service.registerDevice(caller,
                    request(identityKeys.signDevice(deviceKey, credential), List.of(upload(1, false), upload(2, true))));

            assertThat(registered).extracting(CryptoDeviceDto::id, CryptoDeviceDto::sessionId, CryptoDeviceDto::keyPackages)
                    .containsExactly(31L, 12L, 1L);
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

            assertThatThrownBy(() -> service.registerDevice(caller, request(forged, List.of())))
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

            assertThatThrownBy(() -> service.registerDevice(caller, request))
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
                assertThatThrownBy(() -> service.registerDevice(caller, request))
                        .extracting("identifier").isEqualTo(ExceptionIdentifier.SESSION_NOT_FOUND);
            }

            when(sessions.existsByChainIdAndRevokedAtIsNullAndExpiresAtAfter(chain, NOW)).thenReturn(false);
            assertThatThrownBy(() -> service.registerDevice(caller, request(signature, List.of())))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.SESSION_NOT_FOUND);
            verify(devices, never()).save(any());
        }

        @Test
        @DisplayName("needs an identity to check the signature against")
        void needsAnIdentity() {
            when(identities.findById(7)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.registerDevice(caller, request(new byte[64], List.of())))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND);
        }

        @Test
        @DisplayName("the same key package twice, or two last-resort packages, are refused before anything changes")
        void refusesMalformedUploads() {
            byte[] signature = identityKeys.signDevice(deviceKey, credential);

            assertThatThrownBy(() -> service.registerDevice(caller,
                    request(signature, List.of(upload(1, false), upload(1, false)))))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_KEY_PACKAGES);
            assertThatThrownBy(() -> service.registerDevice(caller,
                    request(signature, List.of(upload(1, true), upload(2, true)))))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_KEY_PACKAGES);
            verify(devices, never()).revokeOnChain(any(), any());
        }
    }

    @Nested
    class UploadingKeyPackages {

        private CryptoDevice device;

        @BeforeEach
        void liveDevice() {
            device = new CryptoDevice(caller, UUID.randomUUID(), new byte[32], new byte[4], new byte[64], NOW);
            when(devices.findLive(caller, 3L, NOW)).thenReturn(Optional.of(device));
        }

        @Test
        @DisplayName("adds them to a live device of the caller's and answers with how many are spare")
        void adds() {
            when(keyPackages.countByDeviceAndLastResortFalse(device)).thenReturn(10L, 12L);

            KeyPackageCountDto count = service.uploadKeyPackages(caller, 3L,
                    new KeyPackagesRequest(List.of(upload(1, false), upload(2, false))));

            assertThat(count.available()).isEqualTo(12L);
            verify(keyPackages, never()).deleteLastResortOf(any());
            verify(keyPackages).saveAll(anyList());
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
            List<KeyPackageUpload> two = new ArrayList<>(List.of(upload(1, false), upload(2, false)));

            assertThatThrownBy(() -> service.uploadKeyPackages(caller, 3L, new KeyPackagesRequest(two)))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_KEY_PACKAGES);
            verify(keyPackages, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("a device that is not the caller's, or no longer live, answers 404")
        void unknownDevice() {
            when(devices.findLive(eq(caller), eq(4L), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.uploadKeyPackages(caller, 4L,
                    new KeyPackagesRequest(Collections.singletonList(upload(1, false)))))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.CRYPTO_DEVICE_NOT_FOUND);
        }
    }
}
