package pl.myproject.kanbanproject2.config.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SignedOutSessionInterceptor implements ChannelInterceptor {

    static final String SIGNED_OUT = "This connection was signed out";

    private final StompSessionUsers sessionUsers;

    @Override
    // An ERROR frame is what makes Spring close the socket; dropping the frame would leave it open and silent.
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        var headers = message.getHeaders();
        if (SimpMessageHeaderAccessor.getMessageType(headers) != SimpMessageType.MESSAGE) {
            return message;
        }
        String sessionId = SimpMessageHeaderAccessor.getSessionId(headers);
        if (!sessionUsers.isSignedOut(sessionId)) {
            return message;
        }
        log.info("Closing STOMP session {}: its account was signed out everywhere", sessionId);
        var error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setSessionId(sessionId);
        error.setMessage(SIGNED_OUT);
        return MessageBuilder.createMessage(new byte[0], error.getMessageHeaders());
    }
}
