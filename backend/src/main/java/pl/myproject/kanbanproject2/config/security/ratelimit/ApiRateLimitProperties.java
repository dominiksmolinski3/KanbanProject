package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "security.api-rate-limit")
public record ApiRateLimitProperties(

        @DefaultValue("true") boolean enabled,

        @DefaultValue("20") int perSecond,

        @DefaultValue("100") int burst) {
}
