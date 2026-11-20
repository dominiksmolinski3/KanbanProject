package pl.myproject.kanbanproject2.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.messaging")
public record MessagingProperties(@DefaultValue("true") boolean enabled, @DefaultValue("") String pinKey) {
}
