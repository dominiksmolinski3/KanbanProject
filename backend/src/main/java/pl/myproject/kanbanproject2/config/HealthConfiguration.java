package pl.myproject.kanbanproject2.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HealthConfiguration implements HealthIndicator {

    @Override
    public Health health() {
        return Health.up()
                .withDetail("service", "Kanban API")
                .withDetail("status", "running")
                .build();
    }
}