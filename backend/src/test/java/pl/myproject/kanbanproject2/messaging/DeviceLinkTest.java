package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import pl.myproject.kanbanproject2.board.FixedPrincipalResolver;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalExceptionHandler;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.user;

class DeviceLinkTest {

    private final User ania = user(1, "Ania");
    private ConversationAccess access;
    private DeviceLinkRepository links;
    private DeviceLinkService service;

    private static byte[] key() {
        byte[] key = new byte[65];
        key[0] = 4;
        return key;
    }

    @BeforeEach
    void setUp() {
        access = mock(ConversationAccess.class);
        links = mock(DeviceLinkRepository.class);
        when(links.save(any())).thenAnswer(call -> call.getArgument(0));
        service = new DeviceLinkService(access, links, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a new link replaces any earlier one of the account and lives five minutes")
    void create() {
        DeviceLinkDto created = service.create(ania, new DeviceLinkRequest(key()));

        verify(links).deleteAllOf(1);
        assertThat(created.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(created.devicePublic()).isEqualTo(key());
        assertThat(created.sealed()).isNull();
        assertThat(created.linkerPublic()).isNull();
    }

    @Test
    @DisplayName("only an uncompressed P-256 public key is accepted, for the device and for the approver")
    void keyShape() {
        assertThatThrownBy(() -> service.create(ania, new DeviceLinkRequest(new byte[10])))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CRYPTO_KEY);
        byte[] compressed = key();
        compressed[0] = 2;
        assertThatThrownBy(() -> service.create(ania, new DeviceLinkRequest(compressed)))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CRYPTO_KEY);
        assertThatThrownBy(() -> service.answer(ania, UUID.randomUUID(), new DeviceLinkAnswer(null, new byte[]{1})))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CRYPTO_KEY);
        verify(links, never()).save(any());
    }

    @Test
    @DisplayName("a link is read and answered only by the account that made it, and only while it lives")
    void ownLinksOnly() {
        UUID id = UUID.randomUUID();
        when(links.findLive(eq(id), eq(1), any(Instant.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ania, id))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.DEVICE_LINK_NOT_FOUND);
        assertThatThrownBy(() -> service.answer(ania, id, new DeviceLinkAnswer(key(), new byte[]{1})))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.DEVICE_LINK_NOT_FOUND);
    }

    @Test
    @DisplayName("an approval is stored once, and the new device can read it")
    void answer() {
        DeviceLink link = new DeviceLink(ania, key(), NOW, NOW.plusSeconds(300));
        when(links.findLive(eq(link.getId()), eq(1), any(Instant.class))).thenReturn(Optional.of(link));
        byte[] approver = key();
        approver[1] = 9;

        service.answer(ania, link.getId(), new DeviceLinkAnswer(approver, new byte[]{5, 6}));

        DeviceLinkDto read = service.get(ania, link.getId());
        assertThat(read.sealed()).containsExactly(5, 6);
        assertThat(read.linkerPublic()).isEqualTo(approver);
        assertThatThrownBy(() -> service.answer(ania, link.getId(), new DeviceLinkAnswer(approver, new byte[]{7})))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.DEVICE_LINK_ANSWERED);
        assertThat(link.getSealed()).containsExactly(5, 6);
    }

    @Test
    @DisplayName("an empty or oversized sealed answer is refused")
    void sealedSize() {
        assertThatThrownBy(() -> service.answer(ania, UUID.randomUUID(), new DeviceLinkAnswer(key(), new byte[0])))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CRYPTO_KEY);
        assertThatThrownBy(() -> service.answer(ania, UUID.randomUUID(),
                new DeviceLinkAnswer(key(), new byte[DeviceLinkService.MAX_SEALED_BYTES + 1])))
                .hasFieldOrPropertyWithValue("identifier", ExceptionIdentifier.INVALID_CRYPTO_KEY);
    }

    @Test
    @DisplayName("deleting a link removes it when it is there and is quiet when it is not")
    void remove() {
        DeviceLink link = new DeviceLink(ania, key(), NOW, NOW.plusSeconds(300));
        when(links.findLive(eq(link.getId()), eq(1), any(Instant.class))).thenReturn(Optional.of(link));

        service.delete(ania, link.getId());
        service.delete(ania, UUID.randomUUID());

        ArgumentCaptor<DeviceLink> deleted = ArgumentCaptor.forClass(DeviceLink.class);
        verify(links).delete(deleted.capture());
        assertThat(deleted.getValue()).isSameAs(link);
    }

    @Test
    @DisplayName("the prune removes what has expired")
    void prune() {
        when(links.deleteExpired(NOW)).thenReturn(2);

        new DeviceLinkPrune(links, Clock.fixed(NOW, ZoneOffset.UTC)).prune();

        verify(links).deleteExpired(NOW);
    }

    @Test
    @DisplayName("the endpoints create with 201, read, approve and delete with 204")
    void http() throws Exception {
        DeviceLinkService mocked = mock(DeviceLinkService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DeviceLinkController(mocked))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(ania))
                .build();
        UUID id = UUID.randomUUID();
        when(mocked.create(eq(ania), any())).thenReturn(new DeviceLinkDto(id, key(), null, null, NOW));
        when(mocked.get(ania, id)).thenReturn(new DeviceLinkDto(id, key(), key(), new byte[]{1}, NOW));
        String publicKey = java.util.Base64.getEncoder().encodeToString(key());

        mvc.perform(post("/crypto/links").contentType(APPLICATION_JSON).content("{\"devicePublic\":\"" + publicKey + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.sealed").doesNotExist());
        mvc.perform(get("/crypto/links/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealed").value("AQ=="));
        mvc.perform(post("/crypto/links/" + id + "/answer").contentType(APPLICATION_JSON)
                        .content("{\"linkerPublic\":\"" + publicKey + "\",\"sealed\":\"AQ==\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/crypto/links/" + id)).andExpect(status().isNoContent());
        mvc.perform(post("/crypto/links").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verify(mocked).answer(eq(ania), eq(id), any());
        verify(mocked).delete(ania, id);
    }
}
