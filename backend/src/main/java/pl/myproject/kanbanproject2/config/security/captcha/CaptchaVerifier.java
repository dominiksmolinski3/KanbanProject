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

    record SiteVerifyResponse(boolean success, @JsonProperty("error-codes") List<String> errorCodes) {
    }

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
