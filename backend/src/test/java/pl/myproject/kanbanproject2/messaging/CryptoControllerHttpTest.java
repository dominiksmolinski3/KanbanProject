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

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CryptoControllerHttpTest {

    private static final Base64.Encoder BASE64 = Base64.getEncoder();
    private static final Instant AT = Instant.parse("2026-11-19T10:00:00Z");

    private CryptoKeyService service;
    private PinService pins;
    private MockMvc mvc;
    private ObjectMapper json;
    private User caller;

    @BeforeEach
    void setUp() {
        service = mock(CryptoKeyService.class);
        pins = mock(PinService.class);
        caller = new User();
        caller.setId(7);
        json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new CryptoController(service, pins))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new FixedPrincipalResolver(caller))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    private static String b64(int length) {
        return BASE64.encodeToString(new byte[length]);
    }

    @Test
    @DisplayName("the status carries keys as base64")
    void statusCarriesKeysAsBase64() throws Exception {
        when(service.status(caller)).thenReturn(new CryptoStatusDto(true,
                new IdentityKeyDto(new byte[]{1, 2, 3}, AT, false), new PinStatusDto(true, true, 7),
                List.of(new CryptoDeviceDto(3L, 12L, new byte[]{4}, "Firefox", AT, AT, 100))));

        mvc.perform(get("/crypto/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.identity.publicKey").value("AQID"))
                .andExpect(jsonPath("$.identity.recoveryKey").value(false))
                .andExpect(jsonPath("$.pin.attemptsLeft").value(7))
                .andExpect(jsonPath("$.devices[0].label").value("Firefox"))
                .andExpect(jsonPath("$.devices[0].sessionId").value(12))
                .andExpect(jsonPath("$.devices[0].signatureKey").value("BA=="))
                .andExpect(jsonPath("$.devices[0].keyPackages").value(100));
    }

    @Test
    @DisplayName("creating an identity answers 201, starting over answers 200")
    void identity() throws Exception {
        when(service.createIdentity(eq(caller), any())).thenReturn(new IdentityKeyDto(new byte[32], AT, true));
        when(service.replaceIdentity(eq(caller), any())).thenReturn(new IdentityKeyDto(new byte[32], AT, true));
        String body = json.writeValueAsString(Map.of("publicKey", b64(32), "wrappedPrivate", b64(60)));

        mvc.perform(post("/crypto/identity").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(put("/crypto/identity").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a public key of the wrong length never reaches the service")
    void identityKeyLength() throws Exception {
        String body = json.writeValueAsString(Map.of("publicKey", b64(31), "wrappedPrivate", b64(60)));

        mvc.perform(post("/crypto/identity").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("the backup hands back the wrapped key")
    void backup() throws Exception {
        when(service.backup(caller)).thenReturn(new IdentityBackupDto(new byte[]{1}, new byte[]{2}));

        mvc.perform(get("/crypto/identity/backup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wrappedPrivate").value("Ag=="));
    }

    @Test
    @DisplayName("registering a device answers 201 with the device")
    void registerDevice() throws Exception {
        when(service.registerDevice(eq(caller), any(), eq("Firefox/150")))
                .thenReturn(new CryptoDeviceDto(31L, 12L, new byte[32], "Firefox/150", AT, AT, 100));
        String body = json.writeValueAsString(Map.of(
                "sessionId", 12,
                "signatureKey", b64(32),
                "credential", b64(10),
                "identitySignature", b64(64),
                "keyPackages", List.of(Map.of("ref", b64(32), "data", b64(300), "lastResort", true))));

        mvc.perform(post("/crypto/devices").header("User-Agent", "Firefox/150")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(31));
    }

    @Test
    @DisplayName("a device with a short signature or too many key packages is refused at the door")
    void registerDeviceValidation() throws Exception {
        String shortSignature = json.writeValueAsString(Map.of(
                "sessionId", 12, "signatureKey", b64(32), "credential", b64(10), "identitySignature", b64(63),
                "keyPackages", List.of()));
        List<Map<String, Object>> tooMany = java.util.stream.IntStream.range(0, 102)
                .mapToObj(i -> Map.<String, Object>of("ref", b64(32), "data", b64(10), "lastResort", false)).toList();
        String hoard = json.writeValueAsString(Map.of(
                "sessionId", 12, "signatureKey", b64(32), "credential", b64(10), "identitySignature", b64(64),
                "keyPackages", tooMany));

        mvc.perform(post("/crypto/devices").contentType(MediaType.APPLICATION_JSON).content(shortSignature))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/crypto/devices").contentType(MediaType.APPLICATION_JSON).content(hoard))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a new device may bring a full set of one-time packages and its last-resort package")
    void registerDeviceWithAFullSet() throws Exception {
        when(service.registerDevice(eq(caller), any(), any()))
                .thenReturn(new CryptoDeviceDto(31L, 12L, new byte[32], null, AT, AT, 100));
        List<Map<String, Object>> fullSet = java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> Map.<String, Object>of("ref", b64(32), "data", b64(10), "lastResort", i == 100)).toList();
        String body = json.writeValueAsString(Map.of(
                "sessionId", 12, "signatureKey", b64(32), "credential", b64(10), "identitySignature", b64(64),
                "keyPackages", fullSet));

        mvc.perform(post("/crypto/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("uploading key packages answers with the count, and refusals keep their code")
    void uploadKeyPackages() throws Exception {
        when(service.uploadKeyPackages(eq(caller), eq(3L), any())).thenReturn(new KeyPackageCountDto(42));
        when(service.uploadKeyPackages(eq(caller), eq(4L), any()))
                .thenThrow(new GlobalException(ExceptionIdentifier.CRYPTO_DEVICE_NOT_FOUND));
        String body = json.writeValueAsString(Map.of(
                "keyPackages", List.of(Map.of("ref", b64(32), "data", b64(10), "lastResort", false))));

        mvc.perform(post("/crypto/devices/3/key-packages").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(42));
        mvc.perform(post("/crypto/devices/4/key-packages").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CRYPTO_DEVICE_NOT_FOUND"));
        verify(service).uploadKeyPackages(eq(caller), eq(4L), any());
    }

    @Test
    @DisplayName("an empty upload is refused")
    void emptyUpload() throws Exception {
        mvc.perform(post("/crypto/devices/3/key-packages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyPackages\":[]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a recovery key added later goes in with its signature")
    void setRecoveryKey() throws Exception {
        when(service.setRecoveryKey(eq(caller), any())).thenReturn(new IdentityKeyDto(new byte[32], AT, true));
        String body = json.writeValueAsString(Map.of("wrappedPrivate", b64(60), "signature", b64(64)));

        mvc.perform(put("/crypto/identity/backup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryKey").value(true));
        mvc.perform(put("/crypto/identity/backup").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("wrappedPrivate", b64(60), "signature", b64(10)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the PIN routes: evaluate a blinded point, set a PIN, unlock with a proof")
    void pinRoutes() throws Exception {
        when(pins.evaluate(eq(caller), any())).thenReturn(new PinEvaluationDto(new byte[]{7}));
        when(pins.setPin(eq(caller), any())).thenReturn(new PinStatusDto(true, true, 10));
        when(pins.unlock(eq(caller), any())).thenReturn(new IdentityBackupDto(new byte[]{1}, new byte[]{2}));

        mvc.perform(post("/crypto/pin/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("blinded", b64(65)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluated").value("Bw=="));
        mvc.perform(put("/crypto/pin").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("wrappedPrivate", b64(60), "verifier", b64(32),
                                "signature", b64(64)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptsLeft").value(10));
        mvc.perform(post("/crypto/pin/unlock").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("proof", b64(32)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wrappedPrivate").value("Ag=="));
    }

    @Test
    @DisplayName("PIN refusals keep their codes, and malformed PIN bodies never reach the service")
    void pinRefusals() throws Exception {
        when(pins.unlock(eq(caller), any())).thenThrow(new GlobalException(ExceptionIdentifier.PIN_LOCKED));

        mvc.perform(post("/crypto/pin/unlock").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("proof", b64(32)))))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("PIN_LOCKED"));
        mvc.perform(post("/crypto/pin/evaluate").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("blinded", b64(33)))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/crypto/pin").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("wrappedPrivate", b64(60), "verifier", b64(31),
                                "signature", b64(64)))))
                .andExpect(status().isBadRequest());
        verify(pins).unlock(eq(caller), any());
    }

    @Test
    @DisplayName("a browser re-attaches its device to a new sign-in, or forgets it")
    void attachAndForget() throws Exception {
        when(service.attachSession(eq(caller), eq(3L), any(), eq("Chrome/150")))
                .thenReturn(new CryptoDeviceDto(3L, 20L, new byte[32], "Chrome/150", AT, AT, 90));

        mvc.perform(post("/crypto/devices/3/session").header("User-Agent", "Chrome/150")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("sessionId", 20))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(20));
        mvc.perform(delete("/crypto/devices/3"))
                .andExpect(status().isNoContent());
        verify(service).forgetDevice(caller, 3L);
    }
}
