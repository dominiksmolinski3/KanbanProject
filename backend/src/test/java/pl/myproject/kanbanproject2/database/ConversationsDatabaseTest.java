package pl.myproject.kanbanproject2.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.chat.ChatRepository;
import pl.myproject.kanbanproject2.messaging.ClaimRequest;
import pl.myproject.kanbanproject2.messaging.ClaimedKeyPackageDto;
import pl.myproject.kanbanproject2.messaging.CommitRequest;
import pl.myproject.kanbanproject2.messaging.ConversationAccess;
import pl.myproject.kanbanproject2.messaging.ConversationAccounts;
import pl.myproject.kanbanproject2.messaging.ConversationDto;
import pl.myproject.kanbanproject2.messaging.ConversationDeviceRepository;
import pl.myproject.kanbanproject2.messaging.ConversationKind;
import pl.myproject.kanbanproject2.messaging.ConversationLocks;
import pl.myproject.kanbanproject2.messaging.ConversationMapper;
import pl.myproject.kanbanproject2.messaging.ConversationMemberRepository;
import pl.myproject.kanbanproject2.messaging.ConversationNotifier;
import pl.myproject.kanbanproject2.messaging.ConversationRepository;
import pl.myproject.kanbanproject2.messaging.ConversationService;
import pl.myproject.kanbanproject2.messaging.CreateConversationRequest;
import pl.myproject.kanbanproject2.messaging.CryptoDevice;
import pl.myproject.kanbanproject2.messaging.CryptoDeviceRepository;
import pl.myproject.kanbanproject2.messaging.GroupRestarts;
import pl.myproject.kanbanproject2.messaging.IdentityKey;
import pl.myproject.kanbanproject2.messaging.IdentityKeyRepository;
import pl.myproject.kanbanproject2.messaging.KeyDirectory;
import pl.myproject.kanbanproject2.messaging.KeyLogEntryMapper;
import pl.myproject.kanbanproject2.messaging.KeyLogRepository;
import pl.myproject.kanbanproject2.messaging.KeyPackage;
import pl.myproject.kanbanproject2.messaging.KeyPackageClaimer;
import pl.myproject.kanbanproject2.messaging.KeyPackageRepository;
import pl.myproject.kanbanproject2.messaging.MessageDeliveryService;
import pl.myproject.kanbanproject2.messaging.MessageRequest;
import pl.myproject.kanbanproject2.messaging.MessagingProperties;
import pl.myproject.kanbanproject2.messaging.MlsMessageMapper;
import pl.myproject.kanbanproject2.messaging.MlsMessageRepository;
import pl.myproject.kanbanproject2.messaging.MlsWelcomeRepository;
import pl.myproject.kanbanproject2.messaging.PostedDto;
import pl.myproject.kanbanproject2.messaging.ReadRequest;
import pl.myproject.kanbanproject2.messaging.UserBlockRepository;
import pl.myproject.kanbanproject2.messaging.WelcomeMapper;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.user.UserMapper;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringJUnitConfig({DatabaseTestConfiguration.class, ConversationsDatabaseTest.Beans.class})
class ConversationsDatabaseTest {

    private static final int RACERS = 8;

    @Configuration
    static class Beans {
        @Bean
        MessagingProperties messagingProperties() {
            return new MessagingProperties(true, "");
        }

        @Bean
        ConversationNotifier conversationNotifier() {
            return new ConversationNotifier(mock(SimpMessagingTemplate.class));
        }

        @Bean
        ConversationAccess conversationAccess(MessagingProperties properties, ConversationMemberRepository members,
                                              CryptoDeviceRepository devices) {
            return new ConversationAccess(properties, members, devices);
        }

        @Bean
        ConversationLocks conversationLocks() {
            return new ConversationLocks();
        }

        @Bean
        KeyPackageClaimer keyPackageClaimer(KeyPackageRepository keyPackages) {
            return new KeyPackageClaimer(keyPackages);
        }

        @Bean
        BoardService boardService() {
            return mock(BoardService.class);
        }

        @Bean
        GroupRestarts groupRestarts(ConversationRepository conversations, ConversationDeviceRepository holders,
                                    ConversationMemberRepository members, ConversationLocks locks,
                                    ConversationNotifier notifier) {
            return new GroupRestarts(conversations, holders, members, locks, notifier);
        }

        @Bean
        KeyDirectory keyDirectory(KeyLogRepository log, IdentityKeyRepository identities, MlsWelcomeRepository welcomes,
                                  ConversationRepository conversations, ConversationMemberRepository members,
                                  ConversationNotifier notifier, GroupRestarts restarts) {
            return new KeyDirectory(log, identities, welcomes, conversations, members, notifier, restarts);
        }

        @Bean
        MessageDeliveryService messageDeliveryService(ConversationAccess access, ConversationRepository conversations,
                                                      ConversationLocks locks, ConversationMemberRepository members,
                                                      MlsMessageRepository messages, MlsWelcomeRepository welcomes,
                                                      CryptoDeviceRepository devices, IdentityKeyRepository identities,
                                                      KeyLogRepository keyLog, KeyPackageClaimer claimer,
                                                      ConversationDeviceRepository holders, BoardService boards,
                                                      ConversationNotifier notifier) {
            return new MessageDeliveryService(access, conversations, locks, members, messages, welcomes, devices,
                    identities, keyLog, claimer, holders, boards, notifier, new MlsMessageMapper(), new WelcomeMapper(),
                    new KeyLogEntryMapper());
        }

        @Bean
        ConversationAccounts conversationAccounts(ConversationService conversations) {
            return new ConversationAccounts(conversations);
        }

        @Bean
        UserService userService(UserRepository users, TaskRepository tasks, BoardService boards, ChatRepository chats,
                                ApplicationEventPublisher events) {
            return new UserService(users, new UserMapper(), tasks, boards, chats, events);
        }

        @Bean
        ConversationService conversationService(ConversationAccess access, ConversationRepository conversations,
                                                ConversationLocks locks, ConversationMemberRepository members,
                                                MlsMessageRepository messages, UserBlockRepository blocks,
                                                IdentityKeyRepository identities, CryptoDeviceRepository devices,
                                                UserRepository users, BoardService boards,
                                                ConversationNotifier notifier, GroupRestarts restarts) {
            return new ConversationService(access, conversations, locks, members, messages, blocks, identities,
                    devices, users, boards, notifier, new ConversationMapper(), mock(AuthRateLimiter.class), restarts);
        }
    }

    @Autowired
    private ConversationService conversations;

    @Autowired
    private MessageDeliveryService delivery;

    @Autowired
    private KeyDirectory keyDirectory;

    @Autowired
    private UserService userService;

    @Autowired
    private BoardService boards;

    @Autowired
    private UserRepository users;

    @Autowired
    private IdentityKeyRepository identities;

    @Autowired
    private CryptoDeviceRepository devices;

    @Autowired
    private KeyPackageRepository keyPackages;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private record Person(User user, CryptoDevice device) {
    }

    private Person person(String name) {
        return transactions.execute(status -> {
            var user = new User(name, "conversations-" + UUID.randomUUID() + "@example.test", "hashed");
            user.setEnabled(true);
            User saved = users.save(user);
            identities.save(new IdentityKey(saved, random(32), null, Instant.now()));
            CryptoDevice device = devices.save(new CryptoDevice(saved, UUID.randomUUID(), random(32),
                    ("u" + saved.getId() + "." + "0".repeat(32)).getBytes(), random(64), "test", Instant.now()));
            return new Person(saved, device);
        });
    }

    private static byte[] random(int length) {
        var bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private ConversationDto group(Person creator, Person... others) {
        org.mockito.Mockito.when(boards.shareABoard(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        return conversations.create(creator.user(), new CreateConversationRequest(ConversationKind.GROUP,
                java.util.Arrays.stream(others).map(other -> other.user().getId()).toList(), null, null)).conversation();
    }

    private static <T> List<Future<T>> race(int racers, Callable<T> work) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> results = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return work.call();
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        return results;
    }

    @Test
    @DisplayName("commits racing for one epoch: exactly one is accepted and every other is told the epoch moved on")
    void oneCommitPerEpoch() throws Exception {
        Person ania = person("Ania");
        Person bartek = person("Bartek");
        ConversationDto conversation = group(ania, bartek);

        List<Future<PostedDto>> results = race(RACERS, () -> {
            try {
                return delivery.commit(ania.user(), conversation.id(),
                        new CommitRequest(ania.device().getId(), 0L, random(64), null, null));
            } catch (GlobalException refused) {
                assertThat(refused.getIdentifier()).isEqualTo(ExceptionIdentifier.STALE_EPOCH);
                return null;
            }
        });

        long accepted = 0;
        for (Future<PostedDto> result : results) {
            if (result.get() != null) accepted++;
        }
        assertThat(accepted).isEqualTo(1);
        assertThat(jdbc.queryForObject("select epoch from conversations where id = ?", Long.class, conversation.id()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from mls_messages where conversation_id = ? and kind = 'COMMIT'",
                Integer.class, conversation.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("claims racing for one device's key packages never hand out a one-time package twice")
    void claimsSkipLockedPackages() throws Exception {
        Person ania = person("Ania");
        Person bartek = person("Bartek");
        ConversationDto conversation = group(ania, bartek);
        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < 5; i++) {
                keyPackages.save(new KeyPackage(bartek.device(), random(32), new byte[]{(byte) i}, false, Instant.now()));
            }
            keyPackages.save(new KeyPackage(bartek.device(), random(32), new byte[]{99}, true, Instant.now()));
        });

        List<Future<List<ClaimedKeyPackageDto>>> results = race(RACERS, () ->
                delivery.claim(ania.user(), conversation.id(), new ClaimRequest(List.of(bartek.device().getId()))));

        List<Byte> handedOut = Collections.synchronizedList(new ArrayList<>());
        for (Future<List<ClaimedKeyPackageDto>> result : results) {
            result.get().forEach(claimed -> handedOut.add(claimed.keyPackage()[0]));
        }
        List<Byte> oneTime = handedOut.stream().filter(value -> value != 99).toList();
        assertThat(handedOut).hasSize(RACERS);
        assertThat(new HashSet<>(oneTime)).hasSize(oneTime.size()).hasSize(5);
        assertThat(jdbc.queryForObject("select count(*) from key_packages where device_id = ?", Integer.class,
                bartek.device().getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("unread counts only other people's messages after the read position, and paging reads from the join")
    void unreadAndPaging() {
        Person ania = person("Ania");
        Person bartek = person("Bartek");
        ConversationDto conversation = group(ania, bartek);
        delivery.commit(ania.user(), conversation.id(), new CommitRequest(ania.device().getId(), 0L, random(8), null, null));
        delivery.post(bartek.user(), conversation.id(), new MessageRequest(bartek.device().getId(), 1L, random(8)));
        delivery.post(ania.user(), conversation.id(), new MessageRequest(ania.device().getId(), 1L, random(8)));
        delivery.post(ania.user(), conversation.id(), new MessageRequest(ania.device().getId(), 1L, random(8)));

        assertThat(conversations.get(bartek.user(), conversation.id()).unread()).isEqualTo(2);
        assertThat(conversations.get(ania.user(), conversation.id()).unread()).isZero();
        conversations.markRead(bartek.user(), conversation.id(), new ReadRequest(4L));
        assertThat(conversations.list(bartek.user())).singleElement().satisfies(listed -> assertThat(listed.unread()).isZero());

        Person celina = person("Celina");
        conversations.addMembers(ania.user(), conversation.id(),
                new pl.myproject.kanbanproject2.messaging.AddMembersRequest(List.of(celina.user().getId())));
        assertThat(delivery.messages(celina.user(), conversation.id(), 0L, 100)).isEmpty();
        assertThat(delivery.messages(bartek.user(), conversation.id(), 1L, 2)).extracting(message -> message.seq())
                .containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("a deleted account leaves its groups with a new admin, restarting any only it held, and takes its direct conversations")
    void accountDeletion() {
        Person ania = person("Ania");
        Person bartek = person("Bartek");
        ConversationDto direct = conversations.create(ania.user(),
                new CreateConversationRequest(ConversationKind.DIRECT, List.of(bartek.user().getId()), null, null))
                .conversation();
        ConversationDto group = group(ania, bartek);
        delivery.commit(ania.user(), group.id(), new CommitRequest(ania.device().getId(), 0L, random(8), null, null));

        userService.deleteUser(ania.user().getId());

        assertThat(jdbc.queryForObject("select count(*) from conversations where id = ?", Integer.class, direct.id())).isZero();
        assertThat(jdbc.queryForObject("select role from conversation_members where conversation_id = ?", String.class,
                group.id())).isEqualTo("ADMIN");
        assertThat(jdbc.queryForObject("select epoch from conversations where id = ?", Long.class, group.id()))
                .as("only the deleted account's device held the group, so it starts again for the members left")
                .isZero();
        assertThat(jdbc.queryForObject("select sender_id from mls_messages where conversation_id = ?", Integer.class,
                group.id())).isNull();
    }

    @Test
    @DisplayName("the key log chains each account's entries and asks every conversation the account is in for a commit")
    void keyLogChains() {
        Person ania = person("Ania");
        Person bartek = person("Bartek");
        ConversationDto conversation = group(ania, bartek);
        delivery.commit(ania.user(), conversation.id(), new CommitRequest(ania.device().getId(), 0L, random(8), null, null));

        transactions.executeWithoutResult(status -> {
            keyDirectory.identityChanged(bartek.user(), random(32), Instant.now());
            keyDirectory.deviceAdded(devices.findById(bartek.device().getId()).orElseThrow(), Instant.now());
        });

        List<byte[][]> links = jdbc.query("select prev_hash, entry_hash from key_log where user_id = ? order by id",
                (row, index) -> new byte[][]{row.getBytes(1), row.getBytes(2)}, bartek.user().getId());
        assertThat(links).hasSize(2);
        assertThat(links.get(0)[0]).isNull();
        assertThat(links.get(1)[0]).isEqualTo(links.get(0)[1]);
        assertThat(delivery.keyLog(ania.user(), bartek.user().getId(), null)).hasSize(2);
        assertThat(jdbc.queryForObject("select needs_commit from conversations where id = ?", Boolean.class,
                conversation.id())).isTrue();
    }

    @Test
    @DisplayName("a group whose every holding device is gone restarts at epoch 0 under a new group id, and not before")
    void orphanedGroupsRestart() {
        Person ania = person("Ania");
        Person bartek = person("Bartek");
        ConversationDto conversation = group(ania, bartek);
        delivery.commit(ania.user(), conversation.id(), new CommitRequest(ania.device().getId(), 0L, random(8),
                random(8), List.of(bartek.device().getId())));

        revoke(ania.device());
        assertThat(conversations.get(bartek.user(), conversation.id()).epoch()).isEqualTo(1L);

        revoke(bartek.device());
        ConversationDto restarted = conversations.get(bartek.user(), conversation.id());
        assertThat(restarted.epoch()).isZero();
        assertThat(restarted.groupId()).isNotEqualTo(conversation.groupId());
        assertThat(jdbc.queryForObject("select count(*) from conversation_devices where conversation_id = ?",
                Integer.class, conversation.id())).isZero();
    }

    private void revoke(CryptoDevice device) {
        transactions.executeWithoutResult(status -> {
            CryptoDevice live = devices.findById(device.getId()).orElseThrow();
            live.revokeAt(Instant.now());
            devices.save(live);
            keyDirectory.devicesRevoked(List.of(live), Instant.now());
        });
    }
}
