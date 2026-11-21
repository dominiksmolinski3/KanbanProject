package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class DeviceLinkService {

    static final Duration LIFETIME = Duration.ofMinutes(5);

    static final int PUBLIC_KEY_BYTES = 65;

    static final int MAX_SEALED_BYTES = 2048;

    private final ConversationAccess access;
    private final DeviceLinkRepository links;
    private final Clock clock;

    @Autowired
    public DeviceLinkService(ConversationAccess access, DeviceLinkRepository links) {
        this(access, links, Clock.systemUTC());
    }

    DeviceLinkService(ConversationAccess access, DeviceLinkRepository links, Clock clock) {
        this.access = access;
        this.links = links;
        this.clock = clock;
    }

    @Transactional
    public DeviceLinkDto create(User caller, DeviceLinkRequest request) {
        access.requireEnabled();
        requirePublicKey(request.devicePublic());
        links.deleteAllOf(caller.getId());
        Instant now = clock.instant();
        DeviceLink link = links.save(new DeviceLink(caller, request.devicePublic(), now, now.plus(LIFETIME)));
        return view(link);
    }

    @Transactional
    public DeviceLinkDto get(User caller, UUID id) {
        access.requireEnabled();
        return view(find(caller, id));
    }

    @Transactional
    public void answer(User caller, UUID id, DeviceLinkAnswer answer) {
        access.requireEnabled();
        requirePublicKey(answer.linkerPublic());
        if (answer.sealed().length == 0 || answer.sealed().length > MAX_SEALED_BYTES) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CRYPTO_KEY, "Nieprawidłowa odpowiedź na połączenie");
        }
        DeviceLink link = find(caller, id);
        if (link.isAnswered()) {
            throw new GlobalException(ExceptionIdentifier.DEVICE_LINK_ANSWERED,
                    "To połączenie zostało już zatwierdzone");
        }
        link.answer(answer.linkerPublic(), answer.sealed());
        links.save(link);
    }

    @Transactional
    public void delete(User caller, UUID id) {
        access.requireEnabled();
        links.findLive(id, caller.getId(), clock.instant()).ifPresent(links::delete);
    }

    private DeviceLink find(User caller, UUID id) {
        return links.findLive(id, caller.getId(), clock.instant()).orElseThrow(() ->
                new GlobalException(ExceptionIdentifier.DEVICE_LINK_NOT_FOUND, "Nie znaleziono połączenia urządzeń"));
    }

    private static void requirePublicKey(byte[] key) {
        if (key == null || key.length != PUBLIC_KEY_BYTES || key[0] != 4) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CRYPTO_KEY, "Nieprawidłowy klucz urządzenia");
        }
    }

    private static DeviceLinkDto view(DeviceLink link) {
        return new DeviceLinkDto(link.getId(), link.getDevicePublic(), link.getLinkerPublic(), link.getSealed(),
                link.getExpiresAt());
    }
}
