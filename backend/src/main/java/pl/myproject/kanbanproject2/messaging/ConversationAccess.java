package pl.myproject.kanbanproject2.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

@Component
@RequiredArgsConstructor
public class ConversationAccess {

    private final MessagingProperties properties;
    private final ConversationMemberRepository members;
    private final CryptoDeviceRepository devices;

    public void requireEnabled() {
        if (!properties.enabled()) {
            throw new GlobalException(ExceptionIdentifier.MESSAGING_UNAVAILABLE,
                    "Szyfrowane wiadomości nie są dostępne");
        }
    }

    public ConversationMember requireMember(User caller, Long conversationId) {
        requireEnabled();
        return members.findMembership(conversationId, caller.getId())
                .orElseThrow(ConversationAccess::conversationNotFound);
    }

    public CryptoDevice requireOwnDevice(User caller, Long deviceId) {
        return devices.findLive(caller, deviceId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.CRYPTO_DEVICE_NOT_FOUND,
                        "Nie znaleziono urządzenia do wiadomości"));
    }

    static GlobalException conversationNotFound() {
        return new GlobalException(ExceptionIdentifier.CONVERSATION_NOT_FOUND, "Nie znaleziono rozmowy");
    }
}
