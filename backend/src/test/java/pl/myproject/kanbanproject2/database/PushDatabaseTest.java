package pl.myproject.kanbanproject2.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.messaging.Conversation;
import pl.myproject.kanbanproject2.messaging.ConversationKind;
import pl.myproject.kanbanproject2.messaging.ConversationRepository;
import pl.myproject.kanbanproject2.messaging.CryptoDevice;
import pl.myproject.kanbanproject2.messaging.CryptoDeviceRepository;
import pl.myproject.kanbanproject2.messaging.HistoryVisibility;
import pl.myproject.kanbanproject2.messaging.PushEndpoint;
import pl.myproject.kanbanproject2.messaging.PushEndpointRepository;
import pl.myproject.kanbanproject2.messaging.PushKind;
import pl.myproject.kanbanproject2.messaging.PushOutbox;
import pl.myproject.kanbanproject2.messaging.PushOutboxClaimer;
import pl.myproject.kanbanproject2.messaging.PushOutboxRepository;
import pl.myproject.kanbanproject2.messaging.WhoCanAdd;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(DatabaseTestConfiguration.class)
class PushDatabaseTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private UserRepository users;
    @Autowired
    private CryptoDeviceRepository devices;
    @Autowired
    private ConversationRepository conversations;
    @Autowired
    private PushEndpointRepository endpoints;
    @Autowired
    private PushOutboxRepository outbox;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcTemplate jdbc;

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private record Fixture(User user, CryptoDevice device, PushEndpoint endpoint, Conversation conversation) {
    }

    private Fixture fixture() {
        return transactions.execute(status -> {
            User user = new User("Push", "push-" + UUID.randomUUID() + "@example.test", "hashed");
            user.setEnabled(true);
            User saved = users.save(user);
            CryptoDevice device = devices.save(new CryptoDevice(saved, UUID.randomUUID(), random(32),
                    ("u" + saved.getId() + ".0123456789abcdef0123456789abcdef").getBytes(), random(64), "test", Instant.now()));
            PushEndpoint endpoint = endpoints.save(new PushEndpoint(device, PushKind.FCM, "token-" + UUID.randomUUID(),
                    null, null, Instant.now()));
            Conversation conversation = conversations.save(new Conversation(ConversationKind.GROUP, null, random(16),
                    HistoryVisibility.JOINED, WhoCanAdd.ADMINS, saved, Instant.now()));
            return new Fixture(saved, device, endpoint, conversation);
        });
    }

    private long count(java.util.function.Supplier<Integer> work) {
        return transactions.execute(status -> work.get());
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private long rows(Fixture fixture) {
        return jdbc.queryForObject("SELECT count(*) FROM push_outbox WHERE endpoint_id = ?", Long.class,
                fixture.endpoint().getId());
    }

    @Test
    @DisplayName("queuing again for the same endpoint and conversation keeps one row with the newest seq and a fresh start")
    void oneRowPerConversation() {
        Fixture fixture = fixture();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        transactions.executeWithoutResult(status -> {
            outbox.enqueue(fixture.endpoint().getId(), fixture.conversation().getId(), 4L, now);
            jdbc.update("UPDATE push_outbox SET attempts = 3 WHERE endpoint_id = ?", fixture.endpoint().getId());
            outbox.enqueue(fixture.endpoint().getId(), fixture.conversation().getId(), 9L, now);
        });

        assertThat(rows(fixture)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT seq FROM push_outbox WHERE endpoint_id = ?", Long.class,
                fixture.endpoint().getId())).isEqualTo(9L);
        assertThat(jdbc.queryForObject("SELECT attempts FROM push_outbox WHERE endpoint_id = ?", Integer.class,
                fixture.endpoint().getId())).isZero();
    }

    @Test
    @DisplayName("a claim takes only what is due, leases it, and a second relay cannot take what the first holds")
    void claimsAreExclusive() {
        Fixture fixture = fixture();
        Instant now = Instant.now();
        transactions.executeWithoutResult(status ->
                outbox.enqueue(fixture.endpoint().getId(), fixture.conversation().getId(), 1L, now.minusSeconds(5)));
        PushOutboxClaimer claimer = new PushOutboxClaimer(outbox);

        List<Integer> seenByTheOther = transactions.execute(status -> {
            List<PushOutbox> mine = claimer.claimDue(now, Duration.ofMinutes(2), 1000).stream()
                    .filter(row -> row.getEndpoint().getId().equals(fixture.endpoint().getId())).toList();
            List<PushOutbox> theirs = CompletableFuture.supplyAsync(() -> transactions.execute(inner ->
                    outbox.claimBatch(now, 1000).stream()
                            .filter(row -> row.getEndpoint().getId().equals(fixture.endpoint().getId())).toList())).join();
            return List.of(mine.size(), theirs.size());
        });

        assertThat(seenByTheOther).containsExactly(1, 0);
        Long left = transactions.execute(status -> outbox.claimBatch(now, 1000).stream()
                .filter(row -> row.getEndpoint().getId().equals(fixture.endpoint().getId())).count());
        assertThat(left).isZero();
    }

    @Test
    @DisplayName("a delivered notification is deleted only for the seq that was sent, so a newer one stays queued")
    void deleteDelivered() {
        Fixture fixture = fixture();
        transactions.executeWithoutResult(status ->
                outbox.enqueue(fixture.endpoint().getId(), fixture.conversation().getId(), 8L, Instant.now()));
        Long id = jdbc.queryForObject("SELECT id FROM push_outbox WHERE endpoint_id = ?", Long.class, fixture.endpoint().getId());

        assertThat(count(() -> outbox.deleteDelivered(id, 7L))).isZero();
        assertThat(rows(fixture)).isEqualTo(1);
        assertThat(count(() -> outbox.deleteDelivered(id, 8L))).isEqualTo(1);
        assertThat(rows(fixture)).isZero();
    }

    @Test
    @DisplayName("removing a device or a conversation takes its endpoints and queued notifications with it")
    void cascades() {
        Fixture fixture = fixture();
        Fixture other = fixture();
        transactions.executeWithoutResult(status -> {
            outbox.enqueue(fixture.endpoint().getId(), fixture.conversation().getId(), 1L, Instant.now());
            outbox.enqueue(other.endpoint().getId(), other.conversation().getId(), 1L, Instant.now());
        });

        jdbc.update("DELETE FROM conversations WHERE id = ?", other.conversation().getId());
        assertThat(rows(other)).isZero();
        assertThat(rows(fixture)).isEqualTo(1);

        jdbc.update("DELETE FROM crypto_devices WHERE id = ?", fixture.device().getId());
        assertThat(rows(fixture)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM push_endpoints WHERE id = ?", Long.class,
                fixture.endpoint().getId())).isZero();
    }

    @Test
    @DisplayName("an endpoint is found by device and kind, and only its owner can delete it")
    void endpointQueries() {
        Fixture fixture = fixture();
        Fixture stranger = fixture();

        assertThat(tx(() -> endpoints.findFor(fixture.device().getId(), PushKind.FCM))).isPresent();
        assertThat(tx(() -> endpoints.findFor(fixture.device().getId(), PushKind.WEBPUSH))).isEmpty();
        assertThat(tx(() -> endpoints.findOfUsers(List.of(fixture.user().getId())))).hasSize(1);
        assertThat(count(() -> endpoints.deleteOwn(fixture.endpoint().getId(), stranger.user().getId())))
                .isZero();
        assertThat(count(() -> endpoints.deleteOwn(fixture.endpoint().getId(), fixture.user().getId())))
                .isEqualTo(1);
    }
}
