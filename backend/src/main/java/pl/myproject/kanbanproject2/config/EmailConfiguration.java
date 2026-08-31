package pl.myproject.kanbanproject2.config;

import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * The mail sender, built from {@code spring.mail.*} rather than from a second copy of it.
 *
 * <p>Declaring this bean is what makes Boot's own mail auto-configuration back off, so the host,
 * port, credentials and {@code spring.mail.properties.*} in {@code application.properties} were
 * being read by nothing at all: the host and port were hardcoded here, and {@code mail.debug} was
 * pinned on, which put the whole SMTP dialogue - recipients included - in the application log.
 * {@link MailProperties} is bound explicitly so the properties file is the one place to look.
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class EmailConfiguration {

    private static final String DEFAULT_TIMEOUT_MS = "10000";

    @Bean
    public PersistentSmtpMailSender javaMailSender(MailProperties properties) {
        PersistentSmtpMailSender mailSender = new PersistentSmtpMailSender();
        mailSender.setHost(properties.getHost());
        if (properties.getPort() != null) {
            mailSender.setPort(properties.getPort());
        }
        mailSender.setUsername(properties.getUsername());
        mailSender.setPassword(properties.getPassword());
        if (properties.getProtocol() != null) {
            mailSender.setProtocol(properties.getProtocol());
        }
        if (properties.getDefaultEncoding() != null) {
            mailSender.setDefaultEncoding(properties.getDefaultEncoding().name());
        }

        Properties javaMailProperties = mailSender.getJavaMailProperties();
        javaMailProperties.setProperty("mail.smtp.connectiontimeout", DEFAULT_TIMEOUT_MS);
        javaMailProperties.setProperty("mail.smtp.timeout", DEFAULT_TIMEOUT_MS);
        javaMailProperties.setProperty("mail.smtp.writetimeout", DEFAULT_TIMEOUT_MS);
        javaMailProperties.putAll(properties.getProperties());

        return mailSender;
    }
}
