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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CryptoControllerHttpTest {

    private static final Base64.Encoder BASE64 = Base64.getEncoder();
    private static final Instant AT = Instant.parse("2026-11-19T10:00:00Z");

    private CryptoKeyService service;
    private MockMvc mvc;
    private ObjectMapper json;
    private User caller;

    @BeforeEach
    void setUp() {
        service = mock(CryptoKeyService.class);
        caller = new User();
        caller.setId(7);
        json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new CryptoController(service))
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
        when(service.status(caller)).thenReturn(new CryptoStatusDto(true, new IdentityKeyDto(new byte[]{1, 2, 3}, AT),
                List.of(new CryptoDeviceDto(3L, 12L, new byte[]{4}, AT, 100))));

        mvc.perform(get("/crypto/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.identity.publicKey").value("AQID"))
                .andExpect(jsonPath("$.devices[0].sessionId").value(12))
                .andExpect(jsonPath("$.devices[0].signatureKey").value("BA=="))
                .andExpect(jsonPath("$.devices[0].keyPackages").value(100));
    }

    @Test
    @DisplayName("creating an identity answers 201, starting over answers 200")
    void identity() throws Exception {
        when(service.createIdentity(eq(caller), any())).thenReturn(new IdentityKeyDto(new byte[32], AT));
        when(service.replaceIdentity(eq(caller), any())).thenReturn(new IdentityKeyDto(new byte[32], AT));
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
        when(service.registerDevice(eq(caller), any())).thenReturn(new CryptoDeviceDto(31L, 12L, new byte[32], AT, 100));
        String body = json.writeValueAsString(Map.of(
                "sessionId", 12,
                "signatureKey", b64(32),
                "credential", b64(10),
                "identitySignature", b64(64),
                "keyPackages", List.of(Map.of("ref", b64(32), "data", b64(300), "lastResort", true))));

        mvc.perform(post("/crypto/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(31));
    }

    @Test
    @DisplayName("a device with a short signature or too many key packages is refused at the door")
    void registerDeviceValidation() throws Exception {
        String shortSignature = json.writeValueAsString(Map.of(
                "sessionId", 12, "signatureKey", b64(32), "credential", b64(10), "identitySignature", b64(63),
                "keyPackages", List.of()));
        List<Map<String, Object>> tooMany = java.util.stream.IntStream.range(0, 101)
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
}
