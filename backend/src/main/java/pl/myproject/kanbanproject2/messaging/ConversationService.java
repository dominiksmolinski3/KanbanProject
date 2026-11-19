package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitDimension;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitRule;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ConversationService {

    public static final int MAX_MEMBERS = 100;

    static final int GROUP_ID_BYTES = 16;

    private final ConversationAccess access;
    private final ConversationRepository conversations;
    private final ConversationLocks locks;
    private final ConversationMemberRepository members;
    private final MlsMessageRepository messages;
    private final UserBlockRepository blocks;
    private final IdentityKeyRepository identities;
    private final CryptoDeviceRepository devices;
    private final UserRepository users;
    private final BoardService boards;
    private final ConversationNotifier notifier;
    private final ConversationMapper mapper;
    private final AuthRateLimiter rateLimiter;
    private final GroupRestarts restarts;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ConversationService(ConversationAccess access, ConversationRepository conversations,
                               ConversationLocks locks,
                               ConversationMemberRepository members, MlsMessageRepository messages,
                               UserBlockRepository blocks, IdentityKeyRepository identities,
                               CryptoDeviceRepository devices, UserRepository users, BoardService boards,
                               ConversationNotifier notifier, ConversationMapper mapper,
                               AuthRateLimiter rateLimiter, GroupRestarts restarts) {
        this(access, conversations, locks, members, messages, blocks, identities, devices, users, boards, notifier,
                mapper, rateLimiter, restarts, Clock.systemUTC());
    }

    ConversationService(ConversationAccess access, ConversationRepository conversations,
                        ConversationLocks locks, ConversationMemberRepository members, MlsMessageRepository messages,
                        UserBlockRepository blocks, IdentityKeyRepository identities, CryptoDeviceRepository devices,
                        UserRepository users, BoardService boards, ConversationNotifier notifier,
                        ConversationMapper mapper, AuthRateLimiter rateLimiter, GroupRestarts restarts,
                        Clock clock) {
        this.access = access;
        this.conversations = conversations;
        this.locks = locks;
        this.members = members;
        this.messages = messages;
        this.blocks = blocks;
        this.identities = identities;
        this.devices = devices;
        this.users = users;
        this.boards = boards;
        this.notifier = notifier;
        this.mapper = mapper;
        this.rateLimiter = rateLimiter;
        this.restarts = restarts;
        this.clock = clock;
    }

    public record Created(ConversationDto conversation, boolean created) {
    }

    private record Reachable(User user, boolean peer) {
    }

    @Transactional
    public List<ConversationDto> list(User caller) {
        access.requireEnabled();
        List<ConversationMember> mine = members.findAllOf(caller);
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ConversationMember>> byConversation = members.findAllIn(
                        mine.stream().map(ConversationMember::getConversation).toList()).stream()
                .collect(Collectors.groupingBy(member -> member.getConversation().getId()));
        Map<Long, Long> unread = new HashMap<>();
        messages.countUnread(caller, MlsMessageKind.APPLICATION)
                .forEach(count -> unread.put(count.getConversationId(), count.getUnread()));
        return mine.stream()
                .sorted(Comparator.comparing(
                        (ConversationMember member) -> member.getConversation().getLastActivityAt()).reversed())
                .map(member -> mapper.apply(new ConversationView(member,
                        byConversation.getOrDefault(member.getConversation().getId(), List.of()),
                        unread.getOrDefault(member.getConversation().getId(), 0L))))
                .toList();
    }

    @Transactional
    public ConversationDto get(User caller, Long conversationId) {
        return view(caller, access.requireMember(caller, conversationId));
    }

    @Transactional
    public Created create(User caller, CreateConversationRequest request) {
        access.requireEnabled();
        requireSetUp(caller);
        Set<Integer> others = new LinkedHashSet<>(request.userIds());
        others.remove(caller.getId());
        if (others.isEmpty()) {
            throw invalid("Rozmowa potrzebuje co najmniej jednej innej osoby");
        }
        Instant now = clock.instant();
        if (request.kind() == ConversationKind.DIRECT) {
            if (others.size() != 1) {
                throw invalid("Rozmowa prywatna łączy dokładnie dwie osoby");
            }
            Reachable other = reachable(caller, others.iterator().next());
            String key = directKey(caller.getId(), other.user().getId());
            var existing = conversations.findByDirectKey(key);
            if (existing.isPresent()) {
                return new Created(get(caller, existing.get().getId()), false);
            }
            Conversation conversation = conversations.save(new Conversation(ConversationKind.DIRECT, key, groupId(),
                    HistoryVisibility.ALL, WhoCanAdd.ADMINS, caller, now));
            ConversationMember me = members.save(
                    new ConversationMember(conversation, caller, MemberRole.ADMIN, MemberState.ACTIVE, now));
            members.save(new ConversationMember(conversation, other.user(), MemberRole.ADMIN,
                    other.peer() ? MemberState.ACTIVE : MemberState.REQUEST, now));
            log.info("Account {} started direct conversation {}", caller.getId(), conversation.getId());
            return new Created(announce(caller, me), true);
        }
        List<Reachable> reached = others.stream().map(id -> reachable(caller, id)).toList();
        Conversation conversation = conversations.save(new Conversation(ConversationKind.GROUP, null, groupId(),
                Objects.requireNonNullElse(request.historyVisibility(), HistoryVisibility.JOINED),
                Objects.requireNonNullElse(request.whoCanAdd(), WhoCanAdd.MEMBERS), caller, now));
        ConversationMember me = members.save(
                new ConversationMember(conversation, caller, MemberRole.ADMIN, MemberState.ACTIVE, now));
        for (Reachable other : reached) {
            members.save(new ConversationMember(conversation, other.user(), MemberRole.MEMBER,
                    other.peer() ? MemberState.ACTIVE : MemberState.REQUEST, now));
        }
        log.info("Account {} started group conversation {} with {} other(s)", caller.getId(), conversation.getId(),
                reached.size());
        return new Created(announce(caller, me), true);
    }

    @Transactional
    public ConversationDto update(User caller, Long conversationId, UpdateConversationRequest request) {
        ConversationMember me = access.requireMember(caller, conversationId);
        me.prefer(request.muted(), request.pinned(), request.archived());
        members.save(me);
        boolean settings = request.historyVisibility() != null || request.whoCanAdd() != null;
        if (!settings) {
            notifier.notify(List.of(caller), event(ConversationEventType.MEMBERS, me.getConversation(), caller));
            return view(caller, me);
        }
        requireGroupAdmin(me);
        me.getConversation().configure(request.historyVisibility(), request.whoCanAdd());
        conversations.save(me.getConversation());
        return announce(caller, me);
    }

    @Transactional
    public ConversationDto addMembers(User caller, Long conversationId, AddMembersRequest request) {
        ConversationMember me = access.requireMember(caller, conversationId);
        Conversation conversation = me.getConversation();
        if (conversation.getKind() != ConversationKind.GROUP) {
            throw invalid("Do rozmowy prywatnej nie można dodać osób");
        }
        boolean membersMayAdd = conversation.getWhoCanAdd() == WhoCanAdd.MEMBERS && me.getState() == MemberState.ACTIVE;
        if (!me.isAdmin() && !membersMayAdd) {
            throw notAdmin();
        }
        conversation = locks.lock(conversationId);
        List<ConversationMember> current = members.findAllIn(List.of(conversation));
        Set<Integer> present = current.stream().map(member -> member.getUser().getId()).collect(Collectors.toSet());
        List<Integer> joining = request.userIds().stream().distinct().filter(id -> !present.contains(id)).toList();
        if (current.size() + joining.size() > MAX_MEMBERS) {
            throw invalid("Rozmowa może mieć najwyżej " + MAX_MEMBERS + " osób");
        }
        Instant now = clock.instant();
        for (Integer id : joining) {
            Reachable other = reachable(caller, id);
            members.save(new ConversationMember(conversation, other.user(), MemberRole.MEMBER,
                    other.peer() ? MemberState.ACTIVE : MemberState.REQUEST, now));
        }
        if (!joining.isEmpty()) {
            conversation.requireCommit();
            conversations.save(conversation);
        }
        return announce(caller, me);
    }

    @Transactional
    public void removeMember(User caller, Long conversationId, Integer userId) {
        ConversationMember me = access.requireMember(caller, conversationId);
        Conversation conversation = me.getConversation();
        if (caller.getId().equals(userId)) {
            leave(caller, me);
            return;
        }
        requireGroupAdmin(me);
        ConversationMember target = members.findMembership(conversationId, userId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND,
                        "Tej osoby nie ma w rozmowie"));
        List<User> before = everyone(conversation);
        members.deleteMembership(target.getId());
        conversation.requireCommit();
        conversations.save(conversation);
        notifier.notify(before, event(ConversationEventType.MEMBERS, conversation, caller));
        restarts.restartOrphans(List.of(conversationId));
        log.info("Account {} removed account {} from conversation {}", caller.getId(), userId, conversationId);
    }

    @Transactional
    public ConversationDto setRole(User caller, Long conversationId, Integer userId, MemberRoleRequest request) {
        ConversationMember me = access.requireMember(caller, conversationId);
        requireGroupAdmin(me);
        ConversationMember target = members.findMembership(conversationId, userId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND,
                        "Tej osoby nie ma w rozmowie"));
        if (target.isAdmin() && request.role() != MemberRole.ADMIN
                && members.findAllIn(List.of(me.getConversation())).stream().filter(ConversationMember::isAdmin)
                .count() == 1) {
            throw invalid("Rozmowa musi mieć co najmniej jednego administratora");
        }
        target.setRole(request.role());
        members.save(target);
        return announce(caller, me);
    }

    @Transactional
    public ConversationDto accept(User caller, Long conversationId) {
        ConversationMember me = access.requireMember(caller, conversationId);
        me.accept();
        members.save(me);
        return announce(caller, me);
    }

    @Transactional
    public void markDelivered(User caller, Long conversationId, ReadRequest request) {
        ConversationMember me = requirePosition(caller, conversationId, request);
        if (request.seq() <= me.getDeliveredSeq()) {
            return;
        }
        me.deliveredUpTo(request.seq());
        members.save(me);
        notifier.notify(everyone(me.getConversation()), new ConversationEvent(ConversationEventType.DELIVERED,
                conversationId, request.seq(), caller.getId()));
    }

    @Transactional
    public void markRead(User caller, Long conversationId, ReadRequest request) {
        ConversationMember me = requirePosition(caller, conversationId, request);
        if (request.seq() <= me.getReadSeq()) {
            return;
        }
        me.readUpTo(request.seq());
        members.save(me);
        notifier.notify(everyone(me.getConversation()), new ConversationEvent(ConversationEventType.READ,
                conversationId, request.seq(), caller.getId()));
    }

    private ConversationMember requirePosition(User caller, Long conversationId, ReadRequest request) {
        ConversationMember me = access.requireMember(caller, conversationId);
        if (request.seq() > me.getConversation().getLastSeq()) {
            throw invalid("Nie ma jeszcze takiej wiadomości");
        }
        return me;
    }

    @Transactional
    public void typing(User caller, Long conversationId) {
        ConversationMember me = access.requireMember(caller, conversationId);
        List<User> others = everyone(me.getConversation()).stream()
                .filter(user -> !user.getId().equals(caller.getId()))
                .toList();
        notifier.notify(others, event(ConversationEventType.TYPING, me.getConversation(), caller));
    }

    @Transactional
    public List<ContactDto> contacts(User caller) {
        access.requireEnabled();
        Set<Integer> hidden = new HashSet<>(blocks.blockedBy(caller));
        hidden.addAll(blocks.blockersOf(caller));
        hidden.add(caller.getId());
        List<User> peers = boards.peersOf(caller).stream().filter(user -> !hidden.contains(user.getId())).toList();
        Set<Integer> ready = readyAmong(peers.stream().map(User::getId).toList());
        return peers.stream()
                .sorted(Comparator.comparing(User::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(user -> new ContactDto(user.getId(), user.getName(), user.getEmail(),
                        user.getAvatarBlobName() != null, ready.contains(user.getId())))
                .toList();
    }

    @Transactional
    public ContactDto lookup(User caller, String email) {
        access.requireEnabled();
        if (email == null || email.isBlank() || email.length() > 320) {
            throw invalid("Podaj pełny adres e-mail");
        }
        charge(caller);
        User found = users.findByEmail(email)
                .filter(user -> !user.getId().equals(caller.getId()))
                .filter(user -> !blocks.eitherBlocks(caller.getId(), user.getId()))
                .filter(user -> !readyAmong(List.of(user.getId())).isEmpty())
                .orElseThrow(ConversationService::userNotFound);
        return new ContactDto(found.getId(), found.getName(), found.getEmail(), found.getAvatarBlobName() != null,
                true);
    }

    @Transactional
    public List<BlockDto> blocks(User caller) {
        access.requireEnabled();
        return blocks.findMadeBy(caller).stream()
                .map(block -> new BlockDto(block.getBlocked().getId(), block.getBlocked().getName(),
                        block.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void block(User caller, BlockRequest request) {
        access.requireEnabled();
        if (caller.getId().equals(request.userId())) {
            throw invalid("Nie można zablokować samego siebie");
        }
        User blocked = users.findById(request.userId()).orElseThrow(ConversationService::userNotFound);
        if (!blocks.blocks(caller, blocked.getId())) {
            blocks.save(new UserBlock(caller, blocked, clock.instant()));
        }
        conversations.findByDirectKey(directKey(caller.getId(), blocked.getId())).ifPresent(direct -> {
            List<User> both = everyone(direct);
            conversations.deleteWithContents(direct.getId());
            notifier.notify(both, new ConversationEvent(ConversationEventType.MEMBERS, direct.getId(), null,
                    caller.getId()));
        });
        log.info("Account {} blocked account {}", caller.getId(), blocked.getId());
    }

    @Transactional
    public void unblock(User caller, Integer userId) {
        access.requireEnabled();
        blocks.unblock(caller, userId);
    }

    private void leave(User caller, ConversationMember me) {
        Conversation conversation = me.getConversation();
        List<User> before = everyone(conversation);
        if (conversation.getKind() == ConversationKind.DIRECT) {
            if (me.getState() != MemberState.REQUEST) {
                throw invalid("Rozmowę prywatną można zarchiwizować albo zablokować tę osobę");
            }
            conversations.deleteWithContents(conversation.getId());
            notifier.notify(before, event(ConversationEventType.MEMBERS, conversation, caller));
            log.info("Account {} declined direct conversation {}", caller.getId(), conversation.getId());
            return;
        }
        depart(caller, me, before);
        log.info("Account {} left conversation {}", caller.getId(), conversation.getId());
    }

    @Transactional
    public void forgetAccount(User user) {
        conversations.forgetCreator(user);
        for (ConversationMemberRepository.MembershipRow membership : members.membershipsOf(user)) {
            Long conversationId = membership.getConversationId();
            List<User> before = members.usersIn(conversationId);
            List<ConversationMemberRepository.MembershipRow> remaining = members.membershipsIn(conversationId).stream()
                    .filter(row -> !row.getMemberId().equals(membership.getMemberId()))
                    .toList();
            if (membership.getKind() == ConversationKind.DIRECT || remaining.isEmpty()) {
                conversations.deleteWithContents(conversationId);
            } else {
                members.deleteMembership(membership.getMemberId());
                if (membership.getRole() == MemberRole.ADMIN
                        && remaining.stream().noneMatch(row -> row.getRole() == MemberRole.ADMIN)) {
                    remaining.stream()
                            .min(Comparator.comparing((ConversationMemberRepository.MembershipRow row)
                                            -> row.getState() != MemberState.ACTIVE)
                                    .thenComparing(ConversationMemberRepository.MembershipRow::getJoinedAt)
                                    .thenComparing(ConversationMemberRepository.MembershipRow::getMemberId))
                            .ifPresent(successor -> members.setRole(successor.getMemberId(), MemberRole.ADMIN));
                }
                conversations.requireCommit(conversationId);
                restarts.restartOrphans(List.of(conversationId));
            }
            notifier.notify(before.stream().filter(person -> !person.getId().equals(user.getId())).toList(),
                    new ConversationEvent(ConversationEventType.MEMBERS, conversationId, null, user.getId()));
        }
    }

    private void depart(User leaving, ConversationMember me, List<User> before) {
        Conversation conversation = me.getConversation();
        List<ConversationMember> remaining = members.findAllIn(List.of(conversation)).stream()
                .filter(member -> !member.getId().equals(me.getId()))
                .toList();
        if (remaining.isEmpty()) {
            conversations.deleteWithContents(conversation.getId());
            return;
        }
        members.deleteMembership(me.getId());
        if (me.isAdmin() && remaining.stream().noneMatch(ConversationMember::isAdmin)) {
            ConversationMember successor = remaining.stream()
                    .min(Comparator.comparing((ConversationMember member) -> member.getState() != MemberState.ACTIVE)
                            .thenComparing(ConversationMember::getJoinedAt)
                            .thenComparing(ConversationMember::getId))
                    .orElseThrow();
            successor.setRole(MemberRole.ADMIN);
            members.save(successor);
        }
        conversation.requireCommit();
        conversations.save(conversation);
        notifier.notify(before, event(ConversationEventType.MEMBERS, conversation, leaving));
        restarts.restartOrphans(List.of(conversation.getId()));
    }

    private ConversationDto announce(User caller, ConversationMember me) {
        notifier.notify(everyone(me.getConversation()),
                event(ConversationEventType.MEMBERS, me.getConversation(), caller));
        return view(caller, me);
    }

    private ConversationDto view(User caller, ConversationMember me) {
        long unread = messages.countUnread(caller, MlsMessageKind.APPLICATION).stream()
                .filter(count -> count.getConversationId().equals(me.getConversation().getId()))
                .mapToLong(MlsMessageRepository.UnreadCount::getUnread)
                .findFirst()
                .orElse(0L);
        return mapper.apply(new ConversationView(me, members.findAllIn(List.of(me.getConversation())), unread));
    }

    private List<User> everyone(Conversation conversation) {
        return members.findAllIn(List.of(conversation)).stream().map(ConversationMember::getUser).toList();
    }

    private Reachable reachable(User caller, Integer userId) {
        User target = users.findById(userId).orElseThrow(ConversationService::userNotFound);
        boolean peer = boards.shareABoard(caller, userId);
        if (blocks.eitherBlocks(caller.getId(), userId)) {
            throw userNotFound();
        }
        if (readyAmong(List.of(userId)).isEmpty()) {
            if (peer) {
                throw new GlobalException(ExceptionIdentifier.RECIPIENT_NOT_SET_UP,
                        "Ta osoba nie skonfigurowała jeszcze szyfrowanych wiadomości");
            }
            throw userNotFound();
        }
        if (!peer) {
            charge(caller);
        }
        return new Reachable(target, peer);
    }

    private Set<Integer> readyAmong(List<Integer> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        Set<Integer> withIdentity = identities.findAllById(userIds).stream()
                .map(IdentityKey::getUserId)
                .collect(Collectors.toSet());
        if (withIdentity.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(devices.usersWithLiveDevices(withIdentity));
    }

    private void requireSetUp(User caller) {
        if (readyAmong(List.of(caller.getId())).isEmpty()) {
            throw new GlobalException(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND,
                    "Najpierw skonfiguruj szyfrowane wiadomości na tym urządzeniu");
        }
    }

    private void charge(User caller) {
        if (!rateLimiter.tryConsume(AuthRateLimitRule.CREDENTIALS, AuthRateLimitDimension.ACCOUNT,
                "messaging-reach:" + caller.getId()).allowed()) {
            throw new GlobalException(ExceptionIdentifier.TOO_MANY_REQUESTS);
        }
    }

    private byte[] groupId() {
        byte[] id = new byte[GROUP_ID_BYTES];
        random.nextBytes(id);
        return id;
    }

    static String directKey(Integer one, Integer other) {
        return Math.min(one, other) + ":" + Math.max(one, other);
    }

    private static void requireGroupAdmin(ConversationMember me) {
        if (me.getConversation().getKind() != ConversationKind.GROUP) {
            throw invalid("Rozmowa prywatna nie ma ustawień grupy");
        }
        if (!me.isAdmin()) {
            throw notAdmin();
        }
    }

    private static ConversationEvent event(ConversationEventType type, Conversation conversation, User caller) {
        return new ConversationEvent(type, conversation.getId(), null, caller.getId());
    }

    private static GlobalException invalid(String message) {
        return new GlobalException(ExceptionIdentifier.INVALID_CONVERSATION_REQUEST, message);
    }

    private static GlobalException notAdmin() {
        return new GlobalException(ExceptionIdentifier.NOT_CONVERSATION_ADMIN,
                "Tylko administrator rozmowy może to zrobić");
    }

    private static GlobalException userNotFound() {
        return new GlobalException(ExceptionIdentifier.USER_NOT_FOUND, "Nie znaleziono użytkownika");
    }
}
