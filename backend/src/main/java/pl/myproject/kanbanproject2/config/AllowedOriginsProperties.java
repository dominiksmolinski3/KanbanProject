package pl.myproject.kanbanproject2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "security.cors")
public record AllowedOriginsProperties(

        @DefaultValue({
                "https://kanbanproject.pl",
                "https://www.kanbanproject.pl",
                "http://localhost:5173",
                "http://localhost:3000",
                "http://localhost:5174",
                "http://localhost:8080",
                "http://localhost:80",
                "http://127.0.0.1:8080",
                "http://app:8080"
        })
        List<String> allowedOrigins
) {

    public AllowedOriginsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("security.cors.allowed-origins must not be empty");
        }
        if (allowedOrigins.contains("*")) {
            throw new IllegalArgumentException(
                    "security.cors.allowed-origins cannot be \"*\": these origins are used with credentials");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
    }

    public String[] asArray() {
        return allowedOrigins.toArray(String[]::new);
    }
}
