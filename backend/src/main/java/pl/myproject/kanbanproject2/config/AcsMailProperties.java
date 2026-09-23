package pl.myproject.kanbanproject2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.mail")
public record AcsMailProperties(

        String connectionString,

        String senderAddress,

        @DefaultValue("10s") Duration requestTimeout,

        @DefaultValue("1") int maxRetries,

        String deliveryReportKey) {
    public boolean isConfigured() {
        return StringUtils.hasText(connectionString) && StringUtils.hasText(senderAddress);
    }
}
