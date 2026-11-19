package pl.myproject.kanbanproject2.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.config.security.RefreshTokenService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import pl.myproject.kanbanproject2.messaging.AttachSessionRequest;
import pl.myproject.kanbanproject2.messaging.ConversationDeviceRepository;
import pl.myproject.kanbanproject2.messaging.ConversationLocks;
import pl.myproject.kanbanproject2.messaging.ConversationMemberRepository;
import pl.myproject.kanbanproject2.messaging.ConversationNotifier;
import pl.myproject.kanbanproject2.messaging.ConversationRepository;
import pl.myproject.kanbanproject2.messaging.CryptoDeviceDto;
import pl.myproject.kanbanproject2.messaging.CryptoDeviceMapper;
import pl.myproject.kanbanproject2.messaging.CryptoDeviceRepository;
import pl.myproject.kanbanproject2.messaging.CryptoDeviceSessions;
import pl.myproject.kanbanproject2.messaging.CryptoDeviceSweep;
import pl.myproject.kanbanproject2.messaging.CryptoKeyService;
import pl.myproject.kanbanproject2.messaging.Ed25519TestKeys;
import pl.myproject.kanbanproject2.messaging.IdentityBackupMapper;
import pl.myproject.kanbanproject2.messaging.IdentityKeyMapper;
import pl.myproject.kanbanproject2.messaging.IdentityKeyRepository;
import pl.myproject.kanbanproject2.messaging.IdentityKeyRequest;
import pl.myproject.kanbanproject2.messaging.IdentityPinRepository;
import pl.myproject.kanbanproject2.messaging.GroupRestarts;
import pl.myproject.kanbanproject2.messaging.KeyDirectory;
import pl.myproject.kanbanproject2.messaging.KeyLogRepository;
import pl.myproject.kanbanproject2.messaging.KeyPackageRepository;
import pl.myproject.kanbanproject2.messaging.KeyPackageUpload;
import pl.myproject.kanbanproject2.messaging.KeyPackagesRequest;
import pl.myproject.kanbanproject2.messaging.MessagingProperties;
import pl.myproject.kanbanproject2.messaging.MlsWelcomeRepository;
import pl.myproject.kanbanproject2.messaging.PinEvaluationRequest;
import pl.myproject.kanbanproject2.messaging.PinEvaluator;
import pl.myproject.kanbanproject2.messaging.PinRequest;
import pl.myproject.kanbanproject2.messaging.PinService;
import pl.myproject.kanbanproject2.messaging.RegisterDeviceRequest;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.ActiveDeviceMapper;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
import pl.myproject.kanbanproject2.user.auth.RefreshTokenRepository;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig({DatabaseTestConfiguration.class, MessagingKeysDatabaseTest.Beans.class})
class MessagingKeysDatabaseTest {

    private static final String AGENT = "Mozilla/5.0 (X11; Linux x86_64)";
    private static final byte[] POINT = HexFormat.of().parseHex(
            "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2"
                    + "964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5");

    @Configuration
    static class Beans {
        @Bean
        ActiveDeviceMapper activeDeviceMapper() {
            return new ActiveDeviceMapper();
        }

        @Bean
        RefreshTokenService refreshTokenService(RefreshTokenRepository tokens, ActiveDeviceMapper mapper,
                                                ApplicationEventPublisher events) {
            return new RefreshTokenService(tokens, mapper, 2_592_000_000L, 7_776_000_000L, events);
        }

        @Bean
        MessagingProperties messagingProperties() {
            return new MessagingProperties(true, "test-pin-secret");
        }

        @Bean
        PinService pinService(MessagingProperties properties, IdentityKeyRepository identities,
                              IdentityPinRepository pins) {
            return new PinService(properties, new PinEvaluator(properties), identities, pins);
        }

        @Bean
        ConversationNotifier conversationNotifier() {
            return new ConversationNotifier(org.mockito.Mockito.mock(SimpMessagingTemplate.class));
        }

        @Bean
        ConversationLocks conversationLocks() {
            return new ConversationLocks();
        }

        @Bean
        GroupRestarts groupRestarts(ConversationRepository conversations, ConversationDeviceRepository holders,
                                    ConversationMemberRepository members, ConversationLocks locks,
                                    ConversationNotifier notifier) {
            return new GroupRestarts(conversations, holders, members, locks, notifier);
        }

        @Bean
        KeyDirectory keyDirectory(KeyLogRepository log, IdentityKeyRepository identities,
                                  MlsWelcomeRepository welcomes, ConversationRepository conversations,
                                  ConversationMemberRepository members, ConversationNotifier notifier,
                                  GroupRestarts restarts) {
            return new KeyDirectory(log, identities, welcomes, conversations, members, notifier, restarts);
        }

        @Bean
        CryptoKeyService cryptoKeyService(MessagingProperties properties, IdentityKeyRepository identities,
                                          IdentityPinRepository pins, CryptoDeviceRepository devices,
                                          KeyPackageRepository keyPackages, RefreshTokenRepository sessions,
                                          PinService pinService, KeyDirectory keyDirectory) {
            return new CryptoKeyService(properties, identities, pins, devices, keyPackages, sessions, pinService,
                    new IdentityKeyMapper(), new IdentityBackupMapper(), new CryptoDeviceMapper(), keyDirectory);
        }

        @Bean
        CryptoDeviceSessions cryptoDeviceSessions(CryptoDeviceRepository devices, KeyPackageRepository keyPackages,
                                                  KeyDirectory keyDirectory) {
            return new CryptoDeviceSessions(devices, keyPackages, keyDirectory);
        }

        @Bean
        CryptoDeviceSweep cryptoDeviceSweep(CryptoDeviceRepository devices, KeyPackageRepository keyPackages,
                                            KeyDirectory keyDirectory) {
            return new CryptoDeviceSweep(devices, keyPackages, keyDirectory);
        }
    }

    @Autowired
    private RefreshTokenService sessions;

    @Autowired
    private CryptoKeyService keys;

    @Autowired
    private PinService pins;

    @Autowired
    private CryptoDeviceSweep sweep;

    @Autowired
    private UserRepository users;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private User account() {
        return transactions.execute(status -> {
            var user = new User("someone", "keys-" + UUID.randomUUID() + "@example.test", "hashed");
            user.setEnabled(true);
            return users.save(user);
        });
    }

    private record Account(User user, Ed25519TestKeys identity) {
    }

    private Account withIdentity() {
        var user = account();
        var identity = Ed25519TestKeys.generate();
        keys.createIdentity(user, new IdentityKeyRequest(identity.publicKey(), null));
        return new Account(user, identity);
    }

    private static List<KeyPackageUpload> packages(int count, boolean withLastResort) {
        var uploads = new ArrayList<>(IntStream.range(0, count)
                .mapToObj(i -> new KeyPackageUpload(randomBytes(32), new byte[]{1, 2, 3}, false)).toList());
        if (withLastResort) {
            uploads.add(new KeyPackageUpload(randomBytes(32), new byte[]{4}, true));
        }
        return uploads;
    }

    private static byte[] randomBytes(int length) {
        var bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private CryptoDeviceDto register(Account account, Long sessionId, List<KeyPackageUpload> uploads) {
        byte[] deviceKey = Ed25519TestKeys.generate().publicKey();
        byte[] credential = Ed25519TestKeys.credential(account.user().getId());
        return keys.registerDevice(account.user(), new RegisterDeviceRequest(sessionId, deviceKey, credential,
                account.identity().signDevice(deviceKey, credential), uploads), AGENT);
    }

    private void setPin(Account account) throws Exception {
        byte[] wrapped = {1, 2, 3};
        byte[] verifier = MessageDigest.getInstance("SHA-256").digest(new byte[]{9});
        pins.setPin(account.user(), new PinRequest(wrapped, verifier, account.identity().signPin(wrapped, verifier)));
    }

    private int keyPackagesOf(Long deviceId) {
        return jdbc.queryForObject("select count(*) from key_packages where device_id = ?", Integer.class, deviceId);
    }

    private boolean revokedInTheTable(Long deviceId) {
        return jdbc.queryForObject("select revoked_at is not null from crypto_devices where id = ?", Boolean.class,
                deviceId);
    }

    @Test
    @DisplayName("a device outlives rotations, sign-out and the session's end, and comes back on the next sign-in")
    void deviceOutlivesItsSession() {
        var account = withIdentity();
        var browser = new DeviceContext("203.0.113.1", "browser-a");
        var first = sessions.issue(account.user(), browser);
        var device = register(account, first.sessionId(), packages(3, true));

        var rotation = sessions.rotate(first.token(), browser);
        assertThat(keys.status(account.user()).devices()).singleElement()
                .satisfies(live -> assertThat(live.sessionId()).isEqualTo(rotation.sessionId()));

        sessions.revoke(rotation.refreshToken());
        assertThat(keys.status(account.user()).devices()).singleElement()
                .satisfies(live -> assertThat(live.sessionId()).as("signed out, key kept").isNull());
        assertThat(keyPackagesOf(device.id())).as("three one-time packages and the last-resort one").isEqualTo(4);

        var next = sessions.issue(account.user(), browser);
        var attached = keys.attachSession(account.user(), device.id(), new AttachSessionRequest(next.sessionId()),
                AGENT);

        assertThat(attached.sessionId()).isEqualTo(next.sessionId());
        assertThat(revokedInTheTable(device.id())).isFalse();
    }

    @Test
    @DisplayName("ending a session from Signed-in devices removes that browser's key in the same transaction")
    void endingASessionRemovesItsDevice() {
        var account = withIdentity();
        var laptop = sessions.issue(account.user(), new DeviceContext("203.0.113.1", "laptop"));
        var phone = sessions.issue(account.user(), new DeviceContext("203.0.113.2", "phone"));
        var lost = register(account, laptop.sessionId(), packages(2, true));
        var kept = register(account, phone.sessionId(), packages(2, false));

        sessions.revokeSession(account.user(), laptop.sessionId());

        assertThat(revokedInTheTable(lost.id())).isTrue();
        assertThat(keyPackagesOf(lost.id())).isZero();
        assertThat(keys.status(account.user()).devices()).extracting(CryptoDeviceDto::id).containsExactly(kept.id());
        assertThatThrownBy(() -> keys.attachSession(account.user(), lost.id(),
                new AttachSessionRequest(phone.sessionId()), AGENT))
                .isInstanceOf(GlobalException.class)
                .extracting("identifier").isEqualTo(ExceptionIdentifier.CRYPTO_DEVICE_NOT_FOUND);
    }

    @Test
    @DisplayName("withdrawing every session after a password change keeps the keys")
    void passwordChangeKeepsKeys() {
        var account = withIdentity();
        var session = sessions.issue(account.user(), DeviceContext.unknown());
        var device = register(account, session.sessionId(), packages(1, false));

        sessions.revokeAllFor(account.user());

        assertThat(revokedInTheTable(device.id())).isFalse();
    }

    @Test
    @DisplayName("the sweep revokes devices unused for 180 days and drops their packages, and leaves the rest")
    void sweepRevokesUnusedDevices() {
        var account = withIdentity();
        var old = register(account, sessions.issue(account.user(), new DeviceContext("203.0.113.1", "a")).sessionId(),
                packages(2, true));
        var recent = register(account, sessions.issue(account.user(), new DeviceContext("203.0.113.2", "b"))
                .sessionId(), packages(2, false));
        transactions.executeWithoutResult(status -> jdbc.update(
                "update crypto_devices set last_seen_at = now() - interval '181 days' where id = ?", old.id()));

        sweep.sweep();

        assertThat(revokedInTheTable(old.id())).isTrue();
        assertThat(keyPackagesOf(old.id())).isZero();
        assertThat(revokedInTheTable(recent.id())).isFalse();
        assertThat(keyPackagesOf(recent.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("setting up the same sign-in again replaces its device, and starting over revokes all and drops the PIN")
    void replacingDevicesAndIdentity() throws Exception {
        var account = withIdentity();
        var browser = sessions.issue(account.user(), new DeviceContext("203.0.113.1", "browser-a"));
        var phone = sessions.issue(account.user(), new DeviceContext("203.0.113.9", "phone"));
        var first = register(account, browser.sessionId(), packages(2, true));
        var again = register(account, browser.sessionId(), packages(1, true));
        var onPhone = register(account, phone.sessionId(), packages(1, false));
        setPin(account);

        assertThat(revokedInTheTable(first.id())).isTrue();
        assertThat(keyPackagesOf(first.id())).isZero();

        keys.replaceIdentity(account.user(), new IdentityKeyRequest(Ed25519TestKeys.generate().publicKey(), null));

        assertThat(keys.status(account.user()).devices()).isEmpty();
        assertThat(revokedInTheTable(again.id())).isTrue();
        assertThat(revokedInTheTable(onPhone.id())).isTrue();
        assertThat(keys.status(account.user()).pin().set()).isFalse();
    }

    @Test
    @DisplayName("fifteen guesses at once against a set PIN: exactly ten are answered, the rest find it locked")
    void pinAttemptsAreCountedAtomically() throws Exception {
        var account = withIdentity();
        setPin(account);
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Boolean>> guesses = new ArrayList<>();
        try {
            for (int i = 0; i < 15; i++) {
                guesses.add(pool.submit(() -> {
                    start.await();
                    try {
                        pins.evaluate(account.user(), new PinEvaluationRequest(POINT));
                        return true;
                    } catch (GlobalException refused) {
                        assertThat(refused.getIdentifier()).isEqualTo(ExceptionIdentifier.PIN_LOCKED);
                        return false;
                    }
                }));
            }
            start.countDown();
            List<Boolean> outcomes = new ArrayList<>();
            for (Future<Boolean> guess : guesses) {
                outcomes.add(guess.get(30, TimeUnit.SECONDS));
            }

            assertThat(Collections.frequency(outcomes, true)).isEqualTo(10);
            assertThat(pins.status(account.user()).attemptsLeft()).isZero();
        } finally {
            pool.shutdownNow();
        }

        setPin(account);
        assertThat(pins.status(account.user()).attemptsLeft()).as("a new PIN starts with every attempt").isEqualTo(10);
    }

    @Test
    @DisplayName("a key package reference is unique across every device")
    void keyPackageRefsAreUnique() {
        var account = withIdentity();
        var device = register(account, sessions.issue(account.user(), DeviceContext.unknown()).sessionId(),
                packages(0, false));
        byte[] ref = randomBytes(32);
        keys.uploadKeyPackages(account.user(), device.id(),
                new KeyPackagesRequest(List.of(new KeyPackageUpload(ref, new byte[]{1}, false))));

        var other = withIdentity();
        var otherDevice = register(other, sessions.issue(other.user(), DeviceContext.unknown()).sessionId(),
                packages(0, false));

        assertThatThrownBy(() -> keys.uploadKeyPackages(other.user(), otherDevice.id(),
                new KeyPackagesRequest(List.of(new KeyPackageUpload(ref, new byte[]{2}, false)))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a device cannot be registered on, or moved to, someone else's session")
    void anotherAccountsSession() {
        var account = withIdentity();
        var theirs = sessions.issue(account(), DeviceContext.unknown());
        var device = register(account, sessions.issue(account.user(), DeviceContext.unknown()).sessionId(),
                packages(0, false));

        assertThatThrownBy(() -> register(account, theirs.sessionId(), packages(0, false)))
                .isInstanceOf(GlobalException.class)
                .extracting("identifier").isEqualTo(ExceptionIdentifier.SESSION_NOT_FOUND);
        assertThatThrownBy(() -> keys.attachSession(account.user(), device.id(),
                new AttachSessionRequest(theirs.sessionId()), AGENT))
                .isInstanceOf(GlobalException.class)
                .extracting("identifier").isEqualTo(ExceptionIdentifier.SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("closing the account removes its identity, PIN, devices and key packages with it")
    void accountDeletionCascades() throws Exception {
        var account = withIdentity();
        var device = register(account, sessions.issue(account.user(), DeviceContext.unknown()).sessionId(),
                packages(2, true));
        setPin(account);

        transactions.executeWithoutResult(status ->
                jdbc.update("delete from users where id = ?", account.user().getId()));

        Integer userId = account.user().getId();
        assertThat(jdbc.queryForObject("select count(*) from identity_keys where user_id = ?", Integer.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from identity_pins where user_id = ?", Integer.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from crypto_devices where id = ?", Integer.class, device.id()))
                .isZero();
        assertThat(keyPackagesOf(device.id())).isZero();
    }
}
