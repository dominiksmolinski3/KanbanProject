package pl.myproject.kanbanproject2.config.websocket;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.chat.ChatService;

import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class StompDestinationPolicy implements ChannelInterceptor {

    static final String APPLICATION_PREFIX = "/app/";

    private static final String OWN_USER_PREFIX = "/user";

    private static final Pattern SUBSCRIBABLE = Pattern.compile(
            Pattern.quote(BoardEventPublisher.DESTINATION_PREFIX) + "\\d+(?:" + Pattern.quote(ChatService.CHAT_SUFFIX) + ")?"
                    + "|" + Pattern.quote(OWN_USER_PREFIX + ChatService.DIRECT_QUEUE)
                    + "|" + Pattern.quote(OWN_USER_PREFIX + ChatService.ERROR_QUEUE)
                    + "|" + Pattern.quote(OWN_USER_PREFIX + BoardEventPublisher.BOARDS_QUEUE));

    private final MeterRegistry meterRegistry;

    public static boolean allowsSend(String destination) {
        return destination != null && destination.startsWith(APPLICATION_PREFIX);
    }

    public static boolean allowsSubscribe(String destination) {
        return destination != null && SUBSCRIBABLE.matcher(destination).matches();
    }

    @Override
    // Refusals return null (drop the frame), never throw: an exception closes the session.
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        String destination = accessor.getDestination();
        if (StompCommand.SEND.equals(command) && !allowsSend(destination)) {
            return drop(accessor, destination, "unlisted-send");
        }
        if (StompCommand.SUBSCRIBE.equals(command) && !allowsSubscribe(destination)) {
            return drop(accessor, destination, "unlisted-subscribe");
        }
        return message;
    }

    private Message<?> drop(StompHeaderAccessor accessor, String destination, String reason) {
        var caller = StompPrincipals.userOf(accessor.getUser());
        log.warn("Dropped a {} to {} from {}", accessor.getCommand(), destination,
                caller == null ? "an unauthenticated session" : caller.getUsername());
        meterRegistry.counter(BoardSubscriptionInterceptor.DROPPED_COUNTER, "reason", reason).increment();
        return null;
    }
}
