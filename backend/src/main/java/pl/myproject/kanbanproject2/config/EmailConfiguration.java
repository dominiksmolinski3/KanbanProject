package pl.myproject.kanbanproject2.config;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.core.http.HttpClient;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.util.HttpClientOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.myproject.kanbanproject2.service.EmailSender;

@Slf4j
@Configuration
@EnableConfigurationProperties(AcsMailProperties.class)
public class EmailConfiguration {

    @Bean("mailTransport")
    public EmailSender mailTransport(AcsMailProperties properties) {
        if (!properties.isConfigured()) {
            log.warn("app.mail.connection-string and app.mail.sender-address are not both set; "
                    + "verification, password-reset and deadline mail will be dropped rather than sent");
            return new DisabledEmailSender();
        }
        log.info("Sending mail through Azure Communication Services as {}", properties.senderAddress());
        return new AcsEmailSender(emailClient(properties), properties.senderAddress());
    }

    static EmailClient emailClient(AcsMailProperties properties) {
        HttpClientOptions httpClientOptions = new HttpClientOptions()
                .setConnectTimeout(properties.requestTimeout())
                .setResponseTimeout(properties.requestTimeout())
                .setReadTimeout(properties.requestTimeout())
                .setWriteTimeout(properties.requestTimeout());

        return new EmailClientBuilder()
                .connectionString(properties.connectionString())
                .httpClient(HttpClient.createDefault(httpClientOptions))
                .retryOptions(new RetryOptions(
                        new ExponentialBackoffOptions().setMaxRetries(properties.maxRetries())))
                .buildClient();
    }
}
