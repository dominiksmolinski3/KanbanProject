package pl.myproject.kanbanproject2.config.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import pl.myproject.kanbanproject2.config.security.JwtService;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.time.Instant;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketAuthInterceptorTest {

    private static final MessageChannel CHANNEL = mock(MessageChannel.class);
    private static final String TOKEN = "token";

    private final JwtService jwtService = mock(JwtService.class);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final StompSessionUsers sessionUsers = new StompSessionUsers(mock(UserRepository.class));
    private final WebSocketAuthInterceptor interceptor =
            new WebSocketAuthInterceptor(jwtService, userDetailsService, sessionUsers);

    @BeforeEach
    void setUp() {
        var alice = new User("alice", "alice@example.test", "hashed");
        alice.setId(4);
        when(jwtService.extractUsername(TOKEN)).thenReturn("alice@example.test");
        when(userDetailsService.loadUserByUsername("alice@example.test")).thenReturn(alice);
        when(jwtService.isTokenValid(TOKEN, alice)).thenReturn(true);
        when(jwtService.issuedAt(TOKEN)).thenReturn(Instant.parse("2026-10-21T10:00:00Z"));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    @DisplayName("a connect frame of either name without a token is refused")
    void refusesAConnectWithoutAToken(StompCommand command) {
        assertThatThrownBy(() -> interceptor.preSend(connect(command, null), CHANNEL))
                .as("%s opened a session with no token checked", command)
                .isInstanceOf(AccessDeniedException.class);
        assertThat(sessionUsers.userOf("s1")).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    @DisplayName("a connect frame of either name with a valid token signs the session in")
    void signsInAConnectWithAToken(StompCommand command) {
        Message<?> sent = interceptor.preSend(connect(command, "Bearer " + TOKEN), CHANNEL);

        assertThat(StompPrincipals.userOf(StompHeaderAccessor.wrap(sent).getUser()).getId()).isEqualTo(4);
        assertThat(sessionUsers.userOf("s1")).isEqualTo(4);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    @DisplayName("signing a session in leaves no account behind on the transport thread")
    void leavesTheThreadUnauthenticated(StompCommand command) {
        interceptor.preSend(connect(command, "Bearer " + TOKEN), CHANNEL);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static Message<byte[]> connect(StompCommand command, String authorization) {
        var accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("s1");
        accessor.setSessionAttributes(new HashMap<>());
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
