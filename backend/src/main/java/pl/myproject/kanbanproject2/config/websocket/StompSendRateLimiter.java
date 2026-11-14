package pl.myproject.kanbanproject2.config.websocket;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.config.security.ratelimit.ApiRateLimiter;

@Component
@RequiredArgsConstructor
public class StompSendRateLimiter implements ChannelInterceptor {

    private final ApiRateLimiter limiter;
    private final MeterRegistry meterRegistry;

    @Override
    // A refusal returns null (drop the frame), never throws: an exception closes the session.
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.SEND.equals(accessor.getCommand())) {
            return message;
        }
        var caller = StompPrincipals.userOf(accessor.getUser());
        if (caller == null || limiter.allowsSocketSend(caller.getId())) {
            return message;
        }
        meterRegistry.counter(BoardSubscriptionInterceptor.DROPPED_COUNTER, "reason", "rate-limited").increment();
        return null;
    }
}
