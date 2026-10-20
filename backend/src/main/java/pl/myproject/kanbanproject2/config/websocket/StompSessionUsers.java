package pl.myproject.kanbanproject2.config.websocket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

@Component
public class StompSessionUsers {

    static final Duration VISIBILITY_TTL = Duration.ofSeconds(5);

    private record Verdict(boolean visible, Instant checkedAt) {
    }

    private record Session(Integer userId, Map<Integer, Verdict> boards) {
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public StompSessionUsers() {
        this(Clock.systemUTC());
    }

    StompSessionUsers(Clock clock) {
        this.clock = clock;
    }

    public void register(String sessionId, Integer userId) {
        if (sessionId != null && userId != null) {
            sessions.put(sessionId, new Session(userId, new ConcurrentHashMap<>()));
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
                cached != null && cached.checkedAt().plus(VISIBILITY_TTL).isAfter(now)
                        ? cached
                        : new Verdict(check.getAsBoolean(), now));
        return verdict.visible();
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
