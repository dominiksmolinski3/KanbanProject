package pl.myproject.kanbanproject2.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.user.User;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KeyDirectory {

    static final byte[] LOG_LABEL = "KanbanProject key log v1".getBytes(StandardCharsets.US_ASCII);

    private final KeyLogRepository log;
    private final IdentityKeyRepository identities;
    private final MlsWelcomeRepository welcomes;
    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final ConversationNotifier notifier;
    private final GroupRestarts restarts;

    public void identityChanged(User user, byte[] publicKey, Instant at) {
        append(user, KeyChange.IDENTITY, publicKey, at);
        membersChanged(user);
    }

    public void deviceAdded(CryptoDevice device, Instant at) {
        append(device.getUser(), KeyChange.DEVICE_ADDED, device.getSignatureKey(), at);
        membersChanged(device.getUser());
    }

    public void devicesRevoked(Collection<CryptoDevice> revoked, Instant at) {
        if (revoked.isEmpty()) {
            return;
        }
        welcomes.deleteAllFor(revoked);
        Map<Integer, User> owners = new LinkedHashMap<>();
        for (CryptoDevice device : revoked) {
            owners.putIfAbsent(device.getUser().getId(), device.getUser());
            append(device.getUser(), KeyChange.DEVICE_REMOVED, device.getSignatureKey(), at);
        }
        owners.values().forEach(this::membersChanged);
        owners.values().forEach(owner -> restarts.restartOrphans(members.membershipsOf(owner).stream()
                .map(ConversationMemberRepository.MembershipRow::getConversationId)
                .toList()));
    }

    static byte[] entryHash(byte[] prevHash, KeyChange change, byte[] subject) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(LOG_LABEL);
            if (prevHash != null) {
                digest.update(prevHash);
            }
            digest.update(change.name().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(subject);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void membersChanged(User user) {
        if (conversations.requireCommitFor(user) > 0) {
            List<User> companions = members.findCompanionsOf(user);
            notifier.notify(companions, new ConversationEvent(ConversationEventType.MEMBERS, null, null, user.getId()));
        }
    }

    private void append(User user, KeyChange change, byte[] subject, Instant at) {
        identities.findForUpdate(user.getId());
        byte[] prevHash = log.findFirstByUserOrderByIdDesc(user).map(KeyLogEntry::getEntryHash).orElse(null);
        log.save(new KeyLogEntry(user, change, subject, prevHash, entryHash(prevHash, change, subject), at));
    }
}
