package pl.myproject.kanbanproject2.config.websocket;

import org.springframework.security.core.Authentication;
import pl.myproject.kanbanproject2.user.User;

import java.security.Principal;

/**
 * The account behind a STOMP frame, or {@code null}.
 *
 * <p>{@code WebSocketAuthInterceptor} puts an {@code Authentication} carrying the {@link User} on
 * the session at CONNECT, so every later frame can be traced back to an account - but only by
 * unwrapping it, and an unwrapping written twice is two chances to accept a frame the other would
 * have refused. One method, used by the subscription check and by the chat controller.
 */
public final class StompPrincipals {

    private StompPrincipals() {
    }

    public static User userOf(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof User user) {
            return user;
        }
        return null;
    }
}
