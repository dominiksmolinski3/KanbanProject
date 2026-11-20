package pl.myproject.kanbanproject2.messaging;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MessagingProperties.class, PushProperties.class})
public class MessagingConfiguration {
}
