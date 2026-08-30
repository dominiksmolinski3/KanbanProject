package pl.myproject.kanbanproject2.config.security.captcha;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The one outbound HTTP client this application makes, kept separate from
 * {@link CaptchaVerifier} so the verifier can be handed a client in a test without the timeouts
 * having to be reconstructed around a mock.
 */
@Configuration
@EnableConfigurationProperties(CaptchaProperties.class)
public class CaptchaClientConfig {

    @Bean
    public RestClient captchaRestClient(CaptchaProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
