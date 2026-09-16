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

/**
 * Builds the mail transport from {@link AcsMailProperties}. Mail leaves over the Azure
 * Communication Services Email API rather than the held SMTP connection this replaced, because the
 * deployment is already on Azure and an HTTPS request has no session to keep alive.
 *
 * <p>With no credentials the bean is a {@link DisabledEmailSender} rather than an absence, so
 * nothing downstream needs to know whether mail is configured - a deployment that forgets the
 * connection string starts up and silently sends nothing, and the startup warning below naming the
 * properties is the only thing standing between that and a mystery.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AcsMailProperties.class)
public class EmailConfiguration {

    /**
     * Named, because it is no longer the {@code EmailSender} the application injects. That one is
     * {@code OutboxEmailSender}, which is {@code @Primary} and writes a row; this is what the
     * relay posts with, and the only bean that asks for it asks by name.
     */
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

    /**
     * The SDK finds its HTTP transport through the {@code ServiceLoader}, and the Netty one it
     * reaches for by default is excluded in the POM - the JDK's own client is on the classpath
     * instead. {@link HttpClient#createDefault(HttpClientOptions)} goes through that same lookup,
     * so the timeouts below apply to whichever transport is present rather than to a named one.
     */
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
