package pl.myproject.kanbanproject2.config.security.captcha;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.auth.CaptchaDto;

import java.util.List;

/**
 * Checks a captcha token with the provider that issued it.
 *
 * <p>Until this class existed the widget was decorative end to end: the client rendered it, sent
 * {@code captcha: { token }}, and the token landed on a DTO with no such field - which Spring Boot
 * drops silently, because it disables {@code FAIL_ON_UNKNOWN_PROPERTIES}. Nothing read it and
 * nothing said so, which is worse than having no captcha at all: it reads as a control.
 *
 * <p>Three decisions worth stating, because each of them is a way this could have kept looking
 * like a control without being one.
 *
 * <ul>
 *   <li><b>A missing token is a failure, not a skip.</b> When verification is on, absent and wrong
 *       are the same answer. Otherwise omitting the field is the bypass.
 *   <li><b>An unanswerable check fails closed.</b> A timeout, a 500 from the provider or an
 *       unparseable body all refuse the request. The alternative lets anyone who can keep the
 *       provider from answering walk past. The escape hatch for a real provider outage is
 *       {@code security.captcha.enabled=false}, which is a deliberate act and says so in the
 *       configuration rather than happening quietly under load.
 *   <li><b>Enabled with no secret refuses to start.</b> Every request would fail verification, so
 *       the deployment is broken either way; failing at startup makes it a revision that never goes
 *       healthy rather than a login page nobody can get past.
 * </ul>
 */
@Component
@Slf4j
public class CaptchaVerifier {

    private final CaptchaProperties properties;
    private final RestClient restClient;

    public CaptchaVerifier(CaptchaProperties properties, RestClient captchaRestClient) {
        if (properties.enabled() && properties.secret().isBlank()) {
            throw new IllegalStateException(
                    "security.captcha.enabled is true but no CAPTCHA_SECRET is set - every login "
                            + "and signup would fail verification");
        }
        this.properties = properties;
        this.restClient = captchaRestClient;
    }

    /** The response fields this cares about; the rest of the payload is ignored. */
    record SiteVerifyResponse(boolean success, @JsonProperty("error-codes") List<String> errorCodes) {
    }

    /**
     * Passes silently, or throws {@code 400 CAPTCHA_FAILED}.
     *
     * @param captcha the {@code captcha} object from the request body, which may be absent
     * @param clientIp the address to report to the provider, or {@code null} not to report one
     */
    public void verify(CaptchaDto captcha, String clientIp) {
        if (!properties.enabled()) {
            return;
        }

        String token = captcha == null ? null : captcha.token();
        if (token == null || token.isBlank()) {
            log.debug("Captcha verification refused: no token on the request");
            throw new GlobalException(ExceptionIdentifier.CAPTCHA_FAILED);
        }

        var form = new LinkedMultiValueMap<String, String>();
        form.add("secret", properties.secret());
        form.add("response", token);
        if (clientIp != null && !clientIp.isBlank()) {
            form.add("remoteip", clientIp);
        }

        SiteVerifyResponse response;
        try {
            response = restClient.post()
                    .uri(properties.verifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(SiteVerifyResponse.class);
        } catch (RuntimeException e) {
            // Fails closed. The token cannot be shown to be good, so it is treated as bad.
            log.warn("Captcha verification could not be completed: {}", e.toString());
            throw new GlobalException(ExceptionIdentifier.CAPTCHA_FAILED, e);
        }

        if (response == null || !response.success()) {
            log.debug("Captcha verification failed: {}",
                    response == null ? "no body" : response.errorCodes());
            throw new GlobalException(ExceptionIdentifier.CAPTCHA_FAILED);
        }
    }
}
