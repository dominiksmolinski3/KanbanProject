package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionOperations;
import pl.myproject.kanbanproject2.board.FixedPrincipalResolver;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.NOW;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.conversation;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.device;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.member;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.user;

class PushDeliveryTest {

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final User ania = user(1, "Ania");
    private final User bartek = user(2, "Bartek");
    private final User celina = user(3, "Celina");

    private PushEndpointRepository endpoints;
    private PushOutboxRepository outbox;
    private PushSender web;
    private PushSender fcm;

    @BeforeEach
    void setUp() {
        endpoints = mock(PushEndpointRepository.class);
        outbox = mock(PushOutboxRepository.class);
        web = mock(PushSender.class);
        when(web.kind()).thenReturn(PushKind.WEBPUSH);
        when(web.configured()).thenReturn(true);
        fcm = mock(PushSender.class);
        when(fcm.kind()).thenReturn(PushKind.FCM);
        when(fcm.configured()).thenReturn(false);
    }

    private PushEndpoint endpointOf(long id, User owner, PushKind kind) {
        PushEndpoint endpoint = new PushEndpoint(device(id * 10, owner), kind, "https://fcm.googleapis.com/x" + id,
                URL.encodeToString(new byte[65]), URL.encodeToString(new byte[16]), NOW);
        ReflectionTestUtils.setField(endpoint, "id", id);
        return endpoint;
    }

    @Nested
    class Queueing {

        private final Conversation group = conversation(5, ConversationKind.GROUP, ania);

        @Test
        @DisplayName("everyone but the sender who is active and has not muted gets one queued notification per live endpoint")
        void queues() {
            ConversationMember sender = member(1, group, ania, MemberRole.ADMIN, MemberState.ACTIVE, NOW);
            ConversationMember listening = member(2, group, bartek, MemberRole.MEMBER, MemberState.ACTIVE, NOW);
            ConversationMember muted = member(3, group, celina, MemberRole.MEMBER, MemberState.ACTIVE, NOW);
            muted.prefer(true, null, null);
            ConversationMember invited = member(4, group, user(4, "Darek"), MemberRole.MEMBER, MemberState.REQUEST, NOW);
            PushEndpoint browser = endpointOf(11, bartek, PushKind.WEBPUSH);
            PushEndpoint phone = endpointOf(12, bartek, PushKind.FCM);
            when(endpoints.findOfUsers(java.util.Set.of(2))).thenReturn(List.of(browser, phone));

            new PushQueue(List.of(web, fcm), endpoints, outbox, Clock.fixed(NOW, ZoneOffset.UTC))
                    .messagePosted(group, 9, 1, List.of(sender, listening, muted, invited));

            verify(outbox).enqueue(11L, 5L, 9L, NOW);
            verify(outbox, never()).enqueue(eq(12L), anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("nothing is queued when no sender is configured or nobody is left to tell")
        void idle() {
            when(web.configured()).thenReturn(false);
            new PushQueue(List.of(web, fcm), endpoints, outbox, Clock.fixed(NOW, ZoneOffset.UTC))
                    .messagePosted(group, 9, 1, List.of(member(1, group, bartek, MemberRole.MEMBER, MemberState.ACTIVE, NOW)));
            when(web.configured()).thenReturn(true);
            new PushQueue(List.of(web), endpoints, outbox, Clock.fixed(NOW, ZoneOffset.UTC))
                    .messagePosted(group, 9, 2, List.of(member(2, group, bartek, MemberRole.MEMBER, MemberState.ACTIVE, NOW)));

            verify(endpoints, never()).findOfUsers(any());
            verify(outbox, never()).enqueue(anyLong(), anyLong(), anyLong(), any());
        }
    }

    @Nested
    class Relaying {

        private final PushOutboxClaimer claimer = mock(PushOutboxClaimer.class);
        private PushRelay relay;

        @BeforeEach
        void relay() {
            relay = new PushRelay(claimer, outbox, endpoints, List.of(web, fcm), TransactionOperations.withoutTransaction(),
                    Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private PushOutbox row(long id, PushEndpoint endpoint) {
            PushOutbox row = new PushOutbox(endpoint, 5, 9, NOW);
            ReflectionTestUtils.setField(row, "id", id);
            return row;
        }

        @Test
        @DisplayName("a delivered notification leaves the outbox")
        void delivers() throws Exception {
            PushOutbox row = row(1, endpointOf(11, bartek, PushKind.WEBPUSH));
            when(claimer.claimDue(any(), any(), anyInt())).thenReturn(List.of(row));

            relay.deliverDue();

            verify(web).send(row.getEndpoint(), 5, 9);
            verify(outbox).deleteDelivered(1L, 9L);
        }

        @Test
        @DisplayName("an endpoint the service no longer knows is removed, and its notifications go with it")
        void gone() throws Exception {
            PushEndpoint endpoint = endpointOf(11, bartek, PushKind.WEBPUSH);
            PushOutbox row = row(1, endpoint);
            when(claimer.claimDue(any(), any(), anyInt())).thenReturn(List.of(row));
            doThrow(new PushSender.PushException("gone", null, true)).when(web).send(any(), anyLong(), anyLong());

            relay.deliverDue();

            verify(endpoints).deleteById(11L);
            verify(outbox, never()).save(any());
        }

        @Test
        @DisplayName("a failure is retried later with a growing wait, and given up on after the fifth")
        void retries() throws Exception {
            PushOutbox row = row(1, endpointOf(11, bartek, PushKind.WEBPUSH));
            when(claimer.claimDue(any(), any(), anyInt())).thenReturn(List.of(row));
            doThrow(new PushSender.PushException("busy", null, false)).when(web).send(any(), anyLong(), anyLong());

            relay.deliverDue();
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plus(PushRelay.FIRST_BACKOFF));
            verify(outbox).save(row);

            relay.deliverDue();
            assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plus(PushRelay.FIRST_BACKOFF.multipliedBy(2)));
            relay.deliverDue();
            relay.deliverDue();
            relay.deliverDue();

            assertThat(row.getAttempts()).isEqualTo(PushRelay.MAX_ATTEMPTS);
            verify(outbox).deleteById(1L);
        }

        @Test
        @DisplayName("rows for a kind that is no longer configured are dropped, and nothing runs when nothing is configured")
        void unconfigured() {
            PushOutbox row = row(1, endpointOf(12, bartek, PushKind.FCM));
            when(claimer.claimDue(any(), any(), anyInt())).thenReturn(List.of(row));

            relay.deliverDue();
            verify(outbox).deleteDelivered(1L, 9L);

            when(web.configured()).thenReturn(false);
            org.mockito.Mockito.clearInvocations(claimer);
            relay.deliverDue();
            verify(claimer, never()).claimDue(any(), any(), anyInt());
        }

        @Test
        @DisplayName("the claimer leases what it takes so no one else sends it meanwhile")
        void claims() {
            PushOutbox row = row(1, endpointOf(11, bartek, PushKind.WEBPUSH));
            when(outbox.claimBatch(NOW, 50)).thenReturn(List.of(row));
            when(outbox.saveAll(any())).thenAnswer(call -> call.getArgument(0));

            List<PushOutbox> claimed = new PushOutboxClaimer(outbox).claimDue(NOW, Duration.ofMinutes(2), 50);

            assertThat(claimed).containsExactly(row);
            assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        }
    }

    @Nested
    class Registering {

        private final ConversationAccess access = mock(ConversationAccess.class);
        private final PushProperties configured = new PushProperties("pub", "priv", "mailto:a@b.c", "{}");
        private final CryptoDevice browser = device(10, ania);
        private PushService service;

        @BeforeEach
        void service() {
            when(access.requireOwnDevice(ania, 10L)).thenReturn(browser);
            when(endpoints.save(any())).thenAnswer(call -> {
                PushEndpoint saved = call.getArgument(0);
                ReflectionTestUtils.setField(saved, "id", 77L);
                return saved;
            });
            service = new PushService(access, endpoints, configured, Clock.fixed(NOW, ZoneOffset.UTC));
        }

        private PushEndpointRequest web(String url) {
            return new PushEndpointRequest(10L, PushKind.WEBPUSH, url, URL.encodeToString(new byte[65]),
                    URL.encodeToString(new byte[16]));
        }

        @Test
        @DisplayName("a browser registers its push address once, and registering again replaces it")
        void registers() {
            assertThat(service.register(ania, web("https://fcm.googleapis.com/wp/abc")).id()).isEqualTo(77L);
            PushEndpoint existing = endpointOf(77, ania, PushKind.WEBPUSH);
            when(endpoints.findFor(10L, PushKind.WEBPUSH)).thenReturn(Optional.of(existing));

            service.register(ania, web("https://updates.push.services.mozilla.com/wpush/v2/xyz"));

            assertThat(existing.getEndpoint()).isEqualTo("https://updates.push.services.mozilla.com/wpush/v2/xyz");
        }

        @Test
        @DisplayName("an address that is not a known push service is refused, so the server is never pointed anywhere else")
        void allowlist() {
            for (String address : List.of("http://fcm.googleapis.com/x", "https://evil.example/x",
                    "https://fcm.googleapis.com.evil.example/x", "https://user@fcm.googleapis.com/x",
                    "https://fcm.googleapis.com:8443/x", "https://169.254.169.254/latest", "not a url", "")) {
                assertThatThrownBy(() -> service.register(ania, web(address)))
                        .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_PUSH_ENDPOINT);
            }
            verify(endpoints, never()).save(any());
        }

        @Test
        @DisplayName("the keys of a Web Push subscription must have the right size, and an FCM token only needs to exist")
        void keys() {
            assertThatThrownBy(() -> service.register(ania, new PushEndpointRequest(10L, PushKind.WEBPUSH,
                    "https://fcm.googleapis.com/x", URL.encodeToString(new byte[5]), URL.encodeToString(new byte[16]))))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_PUSH_ENDPOINT);
            assertThatThrownBy(() -> service.register(ania, new PushEndpointRequest(10L, PushKind.WEBPUSH,
                    "https://fcm.googleapis.com/x", null, null)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_PUSH_ENDPOINT);
            assertThatThrownBy(() -> service.register(ania, new PushEndpointRequest(10L, PushKind.FCM, " ", null, null)))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_PUSH_ENDPOINT);

            assertThat(service.register(ania, new PushEndpointRequest(10L, PushKind.FCM, "fcm-token", null, null)).id())
                    .isEqualTo(77L);
        }

        @Test
        @DisplayName("a kind the server has no credentials for cannot be registered, and the config says which are on")
        void configuration() {
            PushService bare = new PushService(access, endpoints, new PushProperties("", "", "", ""));

            assertThatThrownBy(() -> bare.register(ania, web("https://fcm.googleapis.com/x")))
                    .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.PUSH_NOT_CONFIGURED);
            assertThat(bare.config()).isEqualTo(new PushConfigDto(null, false));
            assertThat(service.config()).isEqualTo(new PushConfigDto("pub", true));
        }

        @Test
        @DisplayName("only the owner removes an endpoint")
        void removes() {
            service.remove(ania, 77L);

            verify(endpoints).deleteOwn(77L, 1);
        }

        @Test
        @DisplayName("the endpoints answer 201, give the config and delete with 204")
        void http() throws Exception {
            PushService mocked = mock(PushService.class);
            when(mocked.config()).thenReturn(new PushConfigDto("pub", false));
            when(mocked.register(eq(ania), any())).thenReturn(new PushEndpointDto(5L));
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new PushController(mocked))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .setCustomArgumentResolvers(new FixedPrincipalResolver(ania))
                    .build();

            mvc.perform(get("/push/config")).andExpect(status().isOk()).andExpect(jsonPath("$.webPushKey").value("pub"));
            mvc.perform(post("/push/endpoints").contentType(APPLICATION_JSON)
                            .content("{\"deviceId\":10,\"kind\":\"FCM\",\"endpoint\":\"token\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(5));
            mvc.perform(post("/push/endpoints").contentType(APPLICATION_JSON).content("{\"kind\":\"FCM\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(delete("/push/endpoints/5")).andExpect(status().isNoContent());

            verify(mocked).remove(ania, 5L);
        }
    }
}
