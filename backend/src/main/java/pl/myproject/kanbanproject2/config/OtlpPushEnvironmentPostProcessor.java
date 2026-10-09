package pl.myproject.kanbanproject2.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class OtlpPushEnvironmentPostProcessor implements EnvironmentPostProcessor {
    static final String PASSWORD_PROPERTY = "app.monitoring.otlp-password";
    static final String CA_PROPERTY = "app.monitoring.otlp-ca-pem";
    static final String PUSH_USER = "kanban-api";
    static final String BUNDLE = "monitoring";

    static final String HEADER_PROPERTY = "management.otlp.metrics.export.headers.authorization";
    static final String BUNDLE_CERTIFICATE_PROPERTY = "spring.ssl.bundle.pem." + BUNDLE + ".truststore.certificate";
    static final String EXPORT_BUNDLE_PROPERTY = "management.otlp.metrics.export.ssl.bundle";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> derived = new LinkedHashMap<>();

        String password = environment.getProperty(PASSWORD_PROPERTY, "");
        if (!password.isBlank()) {
            String credential = PUSH_USER + ":" + password;
            derived.put(HEADER_PROPERTY,
                    "Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
        }

        String certificate = environment.getProperty(CA_PROPERTY, "");
        if (!certificate.isBlank()) {
            derived.put(BUNDLE_CERTIFICATE_PROPERTY, certificate);
            derived.put(EXPORT_BUNDLE_PROPERTY, BUNDLE);
        }

        if (!derived.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("otlpPush", derived));
        }
    }
}
