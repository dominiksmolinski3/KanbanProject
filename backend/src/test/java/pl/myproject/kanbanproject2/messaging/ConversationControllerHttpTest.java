package pl.myproject.kanbanproject2.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.board.FixedPrincipalResolver;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.NOW;

class ConversationControllerHttpTest {

    private ConversationService conversations;
    private MessageDeliveryService delivery;
    private CryptoKeyService keys;
    private MockMvc mvc;
    private ObjectMapper json;
    private User caller;

    private static final ConversationDto CONVERSATION = new ConversationDto(5L, ConversationKind.GROUP, null, null,
            new byte[]{1, 2},
            3, true, HistoryVisibility.DAYS_30, WhoCanAdd.ADMINS, 9, NOW, NOW, 2,
            new MembershipDto(MemberRole.ADMIN, MemberState.ACTIVE, 1, 8, 7, false, true, false),
            List.of(new MemberDto(2, "Bartek", false, MemberRole.MEMBER, MemberState.REQUEST, NOW, 1, 6, 5)));

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationService.class);
        delivery = mock(MessageDeliveryService.class);
        keys = mock(CryptoKeyService.class);
        caller = new User();
        caller.setId(1);
        json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new ConversationController(conversations, delivery),
                        new BlockController(conversations), new CryptoController(keys, mock(PinService.class), delivery))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    @Test
    @DisplayName("a conversation carries its group id as base64, its settings and the caller's membership")
    void conversationShape() throws Exception {
        when(conversations.get(caller, 5L)).thenReturn(CONVERSATION);

        mvc.perform(get("/conversations/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value("AQI="))
                .andExpect(jsonPath("$.needsCommit").value(true))
                .andExpect(jsonPath("$.historyVisibility").value("DAYS_30"))
                .andExpect(jsonPath("$.me.pinned").value(true))
                .andExpect(jsonPath("$.members[0].state").value("REQUEST"))
                .andExpect(jsonPath("$.members[0].deliveredSeq").value(6))
                .andExpect(jsonPath("$.members[0].readSeq").value(5))
                .andExpect(jsonPath("$.me.deliveredSeq").value(8));
    }

    @Test
    @DisplayName("starting a conversation answers 201, finding the one that exists answers 200")
    void create() throws Exception {
        var request = Map.of("kind", "DIRECT", "userIds", List.of(2));
        when(conversations.create(eq(caller), any())).thenReturn(new ConversationService.Created(CONVERSATION, true));
        mvc.perform(post("/conversations").contentType(MediaType.APPLICATION_JSON).content(body(request)))
                .andExpect(status().isCreated());

        when(conversations.create(eq(caller), any())).thenReturn(new ConversationService.Created(CONVERSATION, false));
        mvc.perform(post("/conversations").contentType(MediaType.APPLICATION_JSON).content(body(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a conversation with nobody in it, or no kind, is refused before the service")
    void createValidation() throws Exception {
        mvc.perform(post("/conversations").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("kind", "GROUP", "userIds", List.of()))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/conversations").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("userIds", List.of(2)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the list, contacts and lookup answer with their own shapes")
    void reads() throws Exception {
        when(conversations.list(caller)).thenReturn(List.of(CONVERSATION));
        when(conversations.contacts(caller)).thenReturn(List.of(new ContactDto(2, "Bartek", "b@example.com", false, true)));
        when(conversations.lookup(caller, "c@example.com")).thenReturn(new ContactDto(3, "Celina", "c@example.com", true, true));

        mvc.perform(get("/conversations")).andExpect(jsonPath("$[0].id").value(5));
        mvc.perform(get("/conversations/contacts")).andExpect(jsonPath("$[0].messaging").value(true));
        mvc.perform(get("/conversations/lookup").param("email", "c@example.com"))
                .andExpect(jsonPath("$.name").value("Celina"));
    }

    @Test
    @DisplayName("membership routes reach the service with the caller and the ids from the path")
    void membership() throws Exception {
        when(conversations.update(eq(caller), eq(5L), any())).thenReturn(CONVERSATION);
        when(conversations.addMembers(eq(caller), eq(5L), any())).thenReturn(CONVERSATION);
        when(conversations.setRole(eq(caller), eq(5L), eq(2), any())).thenReturn(CONVERSATION);
        when(conversations.accept(caller, 5L)).thenReturn(CONVERSATION);

        mvc.perform(patch("/conversations/5").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("muted", true))))
                .andExpect(status().isOk());
        mvc.perform(post("/conversations/5/members").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("userIds", List.of(3))))).andExpect(status().isOk());
        mvc.perform(put("/conversations/5/members/2").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("role", "ADMIN")))).andExpect(status().isOk());
        mvc.perform(delete("/conversations/5/members/2")).andExpect(status().isNoContent());
        mvc.perform(post("/conversations/5/accept")).andExpect(status().isOk());
        mvc.perform(post("/conversations/5/read").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("seq", 4))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/conversations/5/delivered").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("seq", 6)))).andExpect(status().isNoContent());
        mvc.perform(post("/conversations/5/typing")).andExpect(status().isNoContent());

        verify(conversations).removeMember(caller, 5L, 2);
        verify(conversations).markRead(caller, 5L, new ReadRequest(4L));
        verify(conversations).markDelivered(caller, 5L, new ReadRequest(6L));
        verify(conversations).typing(caller, 5L);
    }

    @Test
    @DisplayName("delivery routes carry bodies as base64, and a stale epoch answers 409 with its code")
    void delivery() throws Exception {
        when(delivery.commit(eq(caller), eq(5L), any())).thenReturn(new PostedDto(4, 2, NOW));
        when(delivery.post(eq(caller), eq(5L), any()))
                .thenThrow(new GlobalException(ExceptionIdentifier.STALE_EPOCH));
        when(delivery.messages(caller, 5L, 3L, 50)).thenReturn(List.of(
                new MlsMessageDto(4, 1, MlsMessageKind.APPLICATION, 2, 20L, new byte[]{9}, NOW)));
        when(delivery.claim(eq(caller), eq(5L), any())).thenReturn(List.of(new ClaimedKeyPackageDto(20L, 2, new byte[]{7})));
        when(delivery.directory(caller, 5L)).thenReturn(List.of(new DirectoryEntryDto(2, new byte[]{1}, List.of())));

        mvc.perform(post("/conversations/5/commits").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("deviceId", 10, "epoch", 1, "body", "AQ=="))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(4));
        mvc.perform(post("/conversations/5/messages").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("deviceId", 10, "epoch", 1, "body", "AQ=="))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_EPOCH"));
        mvc.perform(get("/conversations/5/messages").param("after", "3").param("size", "50"))
                .andExpect(jsonPath("$[0].body").value("CQ=="))
                .andExpect(jsonPath("$[0].kind").value("APPLICATION"));
        mvc.perform(post("/conversations/5/key-packages").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("deviceIds", List.of(20)))))
                .andExpect(jsonPath("$[0].keyPackage").value("Bw=="));
        mvc.perform(get("/conversations/5/devices")).andExpect(jsonPath("$[0].identityKey").value("AQ=="));
    }

    @Test
    @DisplayName("blocks are listed, added and lifted")
    void blocks() throws Exception {
        when(conversations.blocks(caller)).thenReturn(List.of(new BlockDto(2, "Bartek", NOW)));

        mvc.perform(get("/blocks")).andExpect(jsonPath("$[0].userId").value(2));
        mvc.perform(post("/blocks").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("userId", 2))))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/blocks/2")).andExpect(status().isNoContent());

        verify(conversations).block(caller, new BlockRequest(2));
        verify(conversations).unblock(caller, 2);
    }

    @Test
    @DisplayName("a device's welcomes and a contact's key log are read under /crypto")
    void cryptoReads() throws Exception {
        when(delivery.welcomes(caller, 10L)).thenReturn(List.of(new WelcomeDto(8L, 5L, 4, new byte[]{3})));
        when(delivery.keyLog(caller, 2, 6L)).thenReturn(List.of(
                new KeyLogEntryDto(7L, KeyChange.DEVICE_ADDED, new byte[]{1}, null, new byte[]{2}, NOW)));

        mvc.perform(get("/crypto/devices/10/welcomes")).andExpect(jsonPath("$[0].body").value("Aw=="));
        mvc.perform(delete("/crypto/devices/10/welcomes/8")).andExpect(status().isNoContent());
        mvc.perform(get("/crypto/users/2/key-log").param("after", "6"))
                .andExpect(jsonPath("$[0].change").value("DEVICE_ADDED"))
                .andExpect(jsonPath("$[0].prevHash").doesNotExist());

        verify(delivery).dropWelcome(caller, 10L, 8L);
    }
}
