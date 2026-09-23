package pl.myproject.kanbanproject2.config.websocket;

import org.springframework.security.core.Authentication;
import pl.myproject.kanbanproject2.user.User;

import java.security.Principal;

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
