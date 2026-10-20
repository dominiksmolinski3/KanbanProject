package pl.myproject.kanbanproject2.config.websocket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import pl.myproject.kanbanproject2.config.security.JwtService;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@Component
public class StompSessionUsers {

    static final Duration VISIBILITY_TTL = Duration.ofSeconds(5);

    private record Verdict(boolean holds, Instant checkedAt) {

        boolean freshAt(Instant now) {
            return checkedAt.plus(VISIBILITY_TTL).isAfter(now);
        }
    }

    private record Session(Integer userId, Instant tokenIssuedAt, Map<Integer, Verdict> boards,
                           AtomicReference<Verdict> signedOut) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final UserRepository users;
    private final Clock clock;

    @Autowired
    public StompSessionUsers(UserRepository users) {
        this(users, Clock.systemUTC());
    }

    StompSessionUsers(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    public void register(String sessionId, Integer userId, Instant tokenIssuedAt) {
        if (sessionId != null && userId != null && tokenIssuedAt != null) {
            sessions.put(sessionId, new Session(userId, tokenIssuedAt, new ConcurrentHashMap<>(),
                    new AtomicReference<>()));
        }
    }

    public Integer userOf(String sessionId) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        return session == null ? null : session.userId();
    }

    public boolean stillSees(String sessionId, Integer boardId, BooleanSupplier check) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) {
            return true;
        }
        Instant now = clock.instant();
        Verdict verdict = session.boards().compute(boardId, (id, cached) ->
                cached != null && cached.freshAt(now) ? cached : new Verdict(check.getAsBoolean(), now));
        return verdict.holds();
    }

    public boolean isSignedOut(String sessionId) {
        Session session = sessionId == null ? null : sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        Instant now = clock.instant();
        Verdict verdict = session.signedOut().updateAndGet(cached ->
                cached != null && (cached.holds() || cached.freshAt(now))
                        ? cached
                        : new Verdict(JwtService.isRevoked(session.tokenIssuedAt(),
                                users.findSessionsRevokedAt(session.userId()).orElse(null)), now));
        return verdict.holds();
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        if (event.getSessionId() != null) {
            sessions.remove(event.getSessionId());
        }
    }

    int size() {
        return sessions.size();
    }
}
