package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MessageDeliveryService {

    public static final int MAX_HANDSHAKE_BYTES = 1024 * 1024;

    public static final int MAX_MESSAGE_BYTES = 64 * 1024;

    public static final int MAX_CLAIM = 300;

    static final int DEFAULT_PAGE = 25;

    static final int MAX_PAGE = 100;

    static final Duration RETENTION = CryptoDeviceSweep.UNUSED;

    private final ConversationAccess access;
    private final ConversationRepository conversations;
    private final ConversationLocks locks;
    private final ConversationMemberRepository members;
    private final MlsMessageRepository messages;
    private final MlsWelcomeRepository welcomes;
    private final CryptoDeviceRepository devices;
    private final IdentityKeyRepository identities;
    private final KeyLogRepository keyLog;
    private final KeyPackageClaimer claimer;
    private final ConversationDeviceRepository holders;
    private final BoardService boards;
    private final ConversationNotifier notifier;
    private final MlsMessageMapper messageMapper;
    private final WelcomeMapper welcomeMapper;
    private final KeyLogEntryMapper keyLogMapper;
    private final Clock clock;

    @Autowired
    public MessageDeliveryService(ConversationAccess access, ConversationRepository conversations,
                                  ConversationLocks locks,
                                  ConversationMemberRepository members, MlsMessageRepository messages,
                                  MlsWelcomeRepository welcomes, CryptoDeviceRepository devices,
                                  IdentityKeyRepository identities, KeyLogRepository keyLog,
                                  KeyPackageClaimer claimer, ConversationDeviceRepository holders,
                                  BoardService boards, ConversationNotifier notifier,
                                  MlsMessageMapper messageMapper, WelcomeMapper welcomeMapper,
                                  KeyLogEntryMapper keyLogMapper) {
        this(access, conversations, locks, members, messages, welcomes, devices, identities, keyLog, claimer, holders,
                boards, notifier, messageMapper, welcomeMapper, keyLogMapper, Clock.systemUTC());
    }

    MessageDeliveryService(ConversationAccess access, ConversationRepository conversations,
                           ConversationLocks locks, ConversationMemberRepository members, MlsMessageRepository messages,
                           MlsWelcomeRepository welcomes, CryptoDeviceRepository devices,
                           IdentityKeyRepository identities, KeyLogRepository keyLog, KeyPackageClaimer claimer,
                           ConversationDeviceRepository holders, BoardService boards, ConversationNotifier notifier, MlsMessageMapper messageMapper,
                           WelcomeMapper welcomeMapper, KeyLogEntryMapper keyLogMapper, Clock clock) {
        this.access = access;
        this.conversations = conversations;
        this.locks = locks;
        this.members = members;
        this.messages = messages;
        this.welcomes = welcomes;
        this.devices = devices;
        this.identities = identities;
        this.keyLog = keyLog;
        this.claimer = claimer;
        this.holders = holders;
        this.boards = boards;
        this.notifier = notifier;
        this.messageMapper = messageMapper;
        this.welcomeMapper = welcomeMapper;
        this.keyLogMapper = keyLogMapper;
        this.clock = clock;
    }

    @Transactional
    public List<DirectoryEntryDto> directory(User caller, Long conversationId) {
        ConversationMember me = access.requireMember(caller, conversationId);
        List<User> people = members.findAllIn(List.of(me.getConversation())).stream()
                .map(ConversationMember::getUser)
                .toList();
        Map<Integer, byte[]> identityKeys = identities.findAllById(people.stream().map(User::getId).toList()).stream()
                .collect(Collectors.toMap(IdentityKey::getUserId, IdentityKey::getPublicKey));
        Map<Integer, List<DirectoryDeviceDto>> byUser = new LinkedHashMap<>();
        for (CryptoDevice device : devices.findAllOf(people)) {
            byUser.computeIfAbsent(device.getUser().getId(), id -> new ArrayList<>())
                    .add(new DirectoryDeviceDto(device.getId(), device.getSignatureKey(), device.getCredential(),
                            device.getIdentitySignature(), device.getRevokedAt() != null));
        }
        return people.stream()
                .map(person -> new DirectoryEntryDto(person.getId(), identityKeys.get(person.getId()),
                        byUser.getOrDefault(person.getId(), List.of())))
                .toList();
    }

    @Transactional
    public List<ClaimedKeyPackageDto> claim(User caller, Long conversationId, ClaimRequest request) {
        ConversationMember me = access.requireMember(caller, conversationId);
        Set<Integer> memberIds = memberIds(me.getConversation());
        List<ClaimedKeyPackageDto> claimed = new ArrayList<>();
        for (CryptoDevice device : devices.findLiveByIds(new LinkedHashSet<>(request.deviceIds()))) {
            if (!memberIds.contains(device.getUser().getId())) {
                continue;
            }
            claimer.claim(device.getId()).ifPresentOrElse(
                    data -> claimed.add(new ClaimedKeyPackageDto(device.getId(), device.getUser().getId(), data)),
                    () -> log.warn("Messaging device {} has no key package left to claim", device.getId()));
        }
        return claimed;
    }

    @Transactional
    public PostedDto commit(User caller, Long conversationId, CommitRequest request) {
        ConversationMember me = access.requireMember(caller, conversationId);
        CryptoDevice sender = access.requireOwnDevice(caller, request.deviceId());
        Conversation conversation = locks.lock(conversationId);
        if (conversation.getEpoch() != request.epoch()) {
            throw staleEpoch();
        }
        List<CryptoDevice> welcomed = welcomedDevices(conversation, request);
        Instant now = clock.instant();
        long seq = conversation.take(MlsMessageKind.COMMIT, now);
        conversations.save(conversation);
        messages.save(new MlsMessage(conversation, seq, request.epoch(), MlsMessageKind.COMMIT, sender, request.body(),
                now, now.plus(RETENTION)));
        holders.record(conversationId, sender.getId());
        welcomed.forEach(device -> holders.record(conversationId, device.getId()));
        welcomes.saveAll(welcomed.stream()
                .map(device -> new MlsWelcome(device, conversation, seq, request.welcome(), now))
                .toList());
        List<User> everyone = everyone(conversation);
        notifier.notify(everyone,
                new ConversationEvent(ConversationEventType.COMMIT, conversationId, seq, caller.getId()));
        if (!welcomed.isEmpty()) {
            Map<Integer, User> owners = new LinkedHashMap<>();
            welcomed.forEach(device -> owners.putIfAbsent(device.getUser().getId(), device.getUser()));
            notifier.notify(List.copyOf(owners.values()),
                    new ConversationEvent(ConversationEventType.WELCOME, conversationId, seq, caller.getId()));
        }
        log.debug("Account {} committed epoch {} of conversation {} as seq {}", me.getUser().getId(), request.epoch(),
                conversationId, seq);
        return new PostedDto(seq, conversation.getEpoch(), now);
    }

    @Transactional
    public PostedDto post(User caller, Long conversationId, MessageRequest request) {
        ConversationMember me = access.requireMember(caller, conversationId);
        if (me.getConversation().getBoardId() != null) {
            boards.requireWritable(caller, me.getConversation().getBoardId());
        }
        CryptoDevice sender = access.requireOwnDevice(caller, request.deviceId());
        if (request.body().length > MAX_MESSAGE_BYTES) {
            throw new GlobalException(ExceptionIdentifier.MESSAGE_TOO_LARGE,
                    "Wiadomość może mieć najwyżej " + MAX_MESSAGE_BYTES / 1024 + " KB");
        }
        Conversation conversation = locks.lock(conversationId);
        if (conversation.isNeedsCommit() || conversation.getEpoch() != request.epoch()) {
            throw staleEpoch();
        }
        Instant now = clock.instant();
        long seq = conversation.take(MlsMessageKind.APPLICATION, now);
        conversations.save(conversation);
        messages.save(new MlsMessage(conversation, seq, request.epoch(), MlsMessageKind.APPLICATION, sender,
                request.body(), now, now.plus(RETENTION)));
        if (me.getState() == MemberState.REQUEST) {
            me.accept();
        }
        me.readUpTo(seq);
        members.save(me);
        notifier.notify(everyone(conversation),
                new ConversationEvent(ConversationEventType.MESSAGE, conversationId, seq, caller.getId()));
        return new PostedDto(seq, conversation.getEpoch(), now);
    }

    @Transactional
    public List<MlsMessageDto> messages(User caller, Long conversationId, Long after, Integer size) {
        ConversationMember me = access.requireMember(caller, conversationId);
        int pageSize = size == null ? DEFAULT_PAGE : size;
        if (pageSize < 1 || pageSize > MAX_PAGE || (after != null && after < 0)) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CONVERSATION_REQUEST,
                    "Można pobrać od 1 do " + MAX_PAGE + " wiadomości naraz");
        }
        long from = Math.max(after == null ? 0 : after, me.getJoinedSeq() - 1);
        return messages.findAfter(me.getConversation(), from, PageRequest.of(0, pageSize)).stream()
                .map(messageMapper)
                .toList();
    }

    @Transactional
    public List<WelcomeDto> welcomes(User caller, Long deviceId) {
        access.requireEnabled();
        CryptoDevice device = access.requireOwnDevice(caller, deviceId);
        return welcomes.findFor(device).stream().map(welcomeMapper).toList();
    }

    @Transactional
    public void dropWelcome(User caller, Long deviceId, Long welcomeId) {
        access.requireEnabled();
        welcomes.deleteForDevice(welcomeId, access.requireOwnDevice(caller, deviceId));
    }

    @Transactional
    public List<KeyLogEntryDto> keyLog(User caller, Integer userId, Long after) {
        access.requireEnabled();
        boolean visible = caller.getId().equals(userId)
                || members.shareAConversation(caller.getId(), userId)
                || boards.shareABoard(caller, userId);
        if (!visible) {
            throw new GlobalException(ExceptionIdentifier.USER_NOT_FOUND, "Nie znaleziono użytkownika");
        }
        return keyLog.findAfter(userId, after == null ? 0 : after, PageRequest.of(0, MAX_PAGE)).stream()
                .map(keyLogMapper)
                .toList();
    }

    private List<CryptoDevice> welcomedDevices(Conversation conversation, CommitRequest request) {
        List<Long> ids = request.welcomeDeviceIds() == null ? List.of() : request.welcomeDeviceIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        if (request.welcome() == null || request.welcome().length == 0) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CONVERSATION_REQUEST,
                    "Brakuje zaproszenia dla nowych urządzeń");
        }
        Set<Integer> memberIds = memberIds(conversation);
        Map<Long, CryptoDevice> live = devices.findLiveByIds(new LinkedHashSet<>(ids)).stream()
                .filter(device -> memberIds.contains(device.getUser().getId()))
                .collect(Collectors.toMap(CryptoDevice::getId, Function.identity()));
        if (live.size() != new LinkedHashSet<>(ids).size()) {
            throw new GlobalException(ExceptionIdentifier.CRYPTO_DEVICE_NOT_FOUND,
                    "Jedno z urządzeń nie należy już do tej rozmowy");
        }
        return List.copyOf(live.values());
    }

    private Set<Integer> memberIds(Conversation conversation) {
        return members.findAllIn(List.of(conversation)).stream()
                .map(member -> member.getUser().getId())
                .collect(Collectors.toSet());
    }

    private List<User> everyone(Conversation conversation) {
        return members.findAllIn(List.of(conversation)).stream().map(ConversationMember::getUser).toList();
    }

    private static GlobalException staleEpoch() {
        return new GlobalException(ExceptionIdentifier.STALE_EPOCH,
                "Rozmowa zmieniła się w międzyczasie, pobierz nowe wiadomości");
    }
}
