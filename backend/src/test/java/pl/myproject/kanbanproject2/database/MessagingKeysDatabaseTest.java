package pl.myproject.kanbanproject2.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.config.security.RefreshTokenService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.messaging.CryptoDeviceDto;
import pl.myproject.kanbanproject2.messaging.CryptoDeviceMapper;
import pl.myproject.kanbanproject2.messaging.CryptoDeviceRepository;
import pl.myproject.kanbanproject2.messaging.CryptoDeviceSweep;
import pl.myproject.kanbanproject2.messaging.CryptoKeyService;
import pl.myproject.kanbanproject2.messaging.Ed25519TestKeys;
import pl.myproject.kanbanproject2.messaging.IdentityBackupMapper;
import pl.myproject.kanbanproject2.messaging.IdentityKeyMapper;
import pl.myproject.kanbanproject2.messaging.IdentityKeyRepository;
import pl.myproject.kanbanproject2.messaging.IdentityKeyRequest;
import pl.myproject.kanbanproject2.messaging.KeyPackageRepository;
import pl.myproject.kanbanproject2.messaging.KeyPackageUpload;
import pl.myproject.kanbanproject2.messaging.KeyPackagesRequest;
import pl.myproject.kanbanproject2.messaging.MessagingProperties;
import pl.myproject.kanbanproject2.messaging.RegisterDeviceRequest;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.auth.ActiveDeviceMapper;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
import pl.myproject.kanbanproject2.user.auth.RefreshTokenRepository;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig({DatabaseTestConfiguration.class, MessagingKeysDatabaseTest.Beans.class})
class MessagingKeysDatabaseTest {

    @Configuration
    static class Beans {
        @Bean
        ActiveDeviceMapper activeDeviceMapper() {
            return new ActiveDeviceMapper();
        }

        @Bean
        RefreshTokenService refreshTokenService(RefreshTokenRepository tokens, ActiveDeviceMapper mapper) {
            return new RefreshTokenService(tokens, mapper, 2_592_000_000L, 7_776_000_000L);
        }

        @Bean
        CryptoKeyService cryptoKeyService(IdentityKeyRepository identities, CryptoDeviceRepository devices,
                                          KeyPackageRepository keyPackages, RefreshTokenRepository sessions) {
            return new CryptoKeyService(new MessagingProperties(true), identities, devices, keyPackages, sessions,
                    new IdentityKeyMapper(), new IdentityBackupMapper(), new CryptoDeviceMapper());
        }

        @Bean
        CryptoDeviceSweep cryptoDeviceSweep(CryptoDeviceRepository devices, KeyPackageRepository keyPackages) {
            return new CryptoDeviceSweep(devices, keyPackages);
        }
    }

    @Autowired
    private RefreshTokenService sessions;

    @Autowired
    private CryptoKeyService keys;

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
        keys.createIdentity(user, new IdentityKeyRequest(identity.publicKey(), new byte[]{1, 2, 3}));
        return new Account(user, identity);
    }

    private static List<KeyPackageUpload> packages(int count, boolean withLastResort) {
        var uploads = new java.util.ArrayList<>(IntStream.range(0, count)
                .mapToObj(i -> new KeyPackageUpload(randomRef(), new byte[]{1, 2, 3}, false)).toList());
        if (withLastResort) {
            uploads.add(new KeyPackageUpload(randomRef(), new byte[]{4}, true));
        }
        return uploads;
    }

    private static byte[] randomRef() {
        var bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private CryptoDeviceDto register(Account account, Long sessionId, List<KeyPackageUpload> uploads) {
        byte[] deviceKey = Ed25519TestKeys.generate().publicKey();
        byte[] credential = Ed25519TestKeys.credential(account.user().getId());
        return keys.registerDevice(account.user(), new RegisterDeviceRequest(sessionId, deviceKey, credential,
                account.identity().signDevice(deviceKey, credential), uploads));
    }

    private int keyPackagesOf(Long deviceId) {
        return jdbc.queryForObject("select count(*) from key_packages where device_id = ?", Integer.class, deviceId);
    }

    private boolean revokedInTheTable(Long deviceId) {
        return jdbc.queryForObject("select revoked_at is not null from crypto_devices where id = ?", Boolean.class,
                deviceId);
    }

    @Test
    @DisplayName("a device lives exactly as long as its sign-in: rotations keep it, ending the session ends it at once")
    void deviceFollowsItsSession() {
        var account = withIdentity();
        var issued = sessions.issue(account.user(), new DeviceContext("203.0.113.1", "browser-a"));
        var device = register(account, issued.sessionId(), packages(3, true));
        assertThat(device.keyPackages()).isEqualTo(3);

        var rotation = sessions.rotate(issued.token(), new DeviceContext("203.0.113.1", "browser-a"));
        assertThat(keys.status(account.user()).devices())
                .as("a refresh rotates the token row; the device must follow the chain, not the row")
                .singleElement()
                .satisfies(live -> assertThat(live.sessionId()).isEqualTo(rotation.sessionId()));

        sessions.revokeSession(account.user(), rotation.sessionId());

        assertThat(keys.status(account.user()).devices()).isEmpty();
        assertThatThrownBy(() -> keys.uploadKeyPackages(account.user(), device.id(),
                new KeyPackagesRequest(packages(1, false))))
                .isInstanceOf(GlobalException.class)
                .extracting("identifier").isEqualTo(ExceptionIdentifier.CRYPTO_DEVICE_NOT_FOUND);
    }

    @Test
    @DisplayName("signing out and withdrawing every session end devices too, and the sweep records it and drops their packages")
    void everyWayASessionEndsEndsTheDevice() {
        var account = withIdentity();
        var first = sessions.issue(account.user(), new DeviceContext("203.0.113.1", "browser-a"));
        var second = sessions.issue(account.user(), new DeviceContext("203.0.113.2", "browser-b"));
        var third = sessions.issue(account.user(), new DeviceContext("203.0.113.3", "browser-c"));
        var signedOut = register(account, first.sessionId(), packages(2, true));
        var withdrawn = register(account, second.sessionId(), packages(2, false));
        var kept = register(account, third.sessionId(), packages(2, false));

        sessions.revoke(first.token());
        assertThat(keys.status(account.user()).devices()).extracting(CryptoDeviceDto::id)
                .containsExactlyInAnyOrder(withdrawn.id(), kept.id());
        transactions.executeWithoutResult(status ->
                jdbc.update("update refresh_tokens set revoked_at = now() where id = ?", second.sessionId()));
        assertThat(keys.status(account.user()).devices()).extracting(CryptoDeviceDto::id).containsExactly(kept.id());

        sweep.sweep();

        assertThat(revokedInTheTable(signedOut.id())).isTrue();
        assertThat(revokedInTheTable(withdrawn.id())).isTrue();
        assertThat(revokedInTheTable(kept.id())).isFalse();
        assertThat(keyPackagesOf(signedOut.id())).isZero();
        assertThat(keyPackagesOf(withdrawn.id())).isZero();
        assertThat(keyPackagesOf(kept.id())).isEqualTo(2);

        sessions.revokeAllFor(account.user());
        assertThat(keys.status(account.user()).devices()).isEmpty();
    }

    @Test
    @DisplayName("setting up the same browser again replaces its device, and starting over revokes every device")
    void replacingDevicesAndIdentity() {
        var account = withIdentity();
        var browser = sessions.issue(account.user(), new DeviceContext("203.0.113.1", "browser-a"));
        var phone = sessions.issue(account.user(), new DeviceContext("203.0.113.9", "phone"));
        var first = register(account, browser.sessionId(), packages(2, true));
        var again = register(account, browser.sessionId(), packages(1, true));
        var onPhone = register(account, phone.sessionId(), packages(1, false));

        assertThat(revokedInTheTable(first.id())).isTrue();
        assertThat(keyPackagesOf(first.id())).isZero();
        assertThat(keys.status(account.user()).devices()).extracting(CryptoDeviceDto::id)
                .containsExactlyInAnyOrder(again.id(), onPhone.id());

        keys.replaceIdentity(account.user(),
                new IdentityKeyRequest(Ed25519TestKeys.generate().publicKey(), new byte[]{7}));

        assertThat(keys.status(account.user()).devices()).isEmpty();
        assertThat(revokedInTheTable(again.id())).isTrue();
        assertThat(revokedInTheTable(onPhone.id())).isTrue();
        assertThat(keyPackagesOf(again.id()) + keyPackagesOf(onPhone.id())).isZero();
        assertThat(keys.backup(account.user()).wrappedPrivate()).containsExactly(7);
    }

    @Test
    @DisplayName("a key package reference is unique across every device")
    void keyPackageRefsAreUnique() {
        var account = withIdentity();
        var session = sessions.issue(account.user(), DeviceContext.unknown());
        var device = register(account, session.sessionId(), packages(0, false));
        byte[] ref = randomRef();
        keys.uploadKeyPackages(account.user(), device.id(),
                new KeyPackagesRequest(List.of(new KeyPackageUpload(ref, new byte[]{1}, false))));

        var other = withIdentity();
        var otherSession = sessions.issue(other.user(), DeviceContext.unknown());
        var otherDevice = register(other, otherSession.sessionId(), packages(0, false));

        assertThatThrownBy(() -> keys.uploadKeyPackages(other.user(), otherDevice.id(),
                new KeyPackagesRequest(List.of(new KeyPackageUpload(ref, new byte[]{2}, false)))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a device cannot be registered on someone else's session")
    void anotherAccountsSession() {
        var account = withIdentity();
        var stranger = account();
        var theirs = sessions.issue(stranger, DeviceContext.unknown());

        assertThatThrownBy(() -> register(account, theirs.sessionId(), packages(0, false)))
                .isInstanceOf(GlobalException.class)
                .extracting("identifier").isEqualTo(ExceptionIdentifier.SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("closing the account removes its identity, devices and key packages with it")
    void accountDeletionCascades() {
        var account = withIdentity();
        var session = sessions.issue(account.user(), DeviceContext.unknown());
        var device = register(account, session.sessionId(), packages(2, true));

        transactions.executeWithoutResult(status ->
                jdbc.update("delete from users where id = ?", account.user().getId()));

        assertThat(jdbc.queryForObject("select count(*) from identity_keys where user_id = ?", Integer.class,
                account.user().getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from crypto_devices where id = ?", Integer.class, device.id()))
                .isZero();
        assertThat(keyPackagesOf(device.id())).isZero();
    }
}
