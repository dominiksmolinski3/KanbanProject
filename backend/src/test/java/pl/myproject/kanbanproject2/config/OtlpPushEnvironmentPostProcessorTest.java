package pl.myproject.kanbanproject2.config;

import io.micrometer.registry.otlp.OtlpConfig;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpPushEnvironmentPostProcessorTest {
    private static final String PASSWORD = "s3cret-push-password";
    private static final String EXPECTED_HEADER = "Basic "
            + Base64.getEncoder().encodeToString(("kanban-api:" + PASSWORD).getBytes(StandardCharsets.UTF_8));

    @Test
    @DisplayName("the push header is built from the one stored password")
    void buildsTheHeaderFromThePassword() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(OtlpPushEnvironmentPostProcessor.PASSWORD_PROPERTY, PASSWORD);

        new OtlpPushEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(OtlpPushEnvironmentPostProcessor.HEADER_PROPERTY)).isEqualTo(EXPECTED_HEADER);
        assertThat(environment.containsProperty(OtlpPushEnvironmentPostProcessor.EXPORT_BUNDLE_PROPERTY)).isFalse();
    }

    @Test
    @DisplayName("with nothing configured, the export is left exactly as the local stack uses it")
    void changesNothingWithoutSettings() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(OtlpPushEnvironmentPostProcessor.PASSWORD_PROPERTY, "")
                .withProperty(OtlpPushEnvironmentPostProcessor.CA_PROPERTY, "");

        new OtlpPushEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().contains("otlpPush")).isFalse();
    }

    @Test
    @DisplayName("Boot binds the header and builds an SSL bundle that only the OTLP exporter uses")
    void bootBindsWhatThePostProcessorWrites() throws IOException {
        String ca = testCa();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class, SslAutoConfiguration.class, OtlpMetricsExportAutoConfiguration.class))
                .withPropertyValues(
                        "management.otlp.metrics.export.enabled=true",
                        "management.otlp.metrics.export.url=https://127.0.0.1:1/api/v1/otlp/v1/metrics",
                        OtlpPushEnvironmentPostProcessor.PASSWORD_PROPERTY + "=" + PASSWORD,
                        OtlpPushEnvironmentPostProcessor.CA_PROPERTY + "=" + ca)
                .withInitializer(context -> new OtlpPushEnvironmentPostProcessor()
                        .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(OtlpMeterRegistry.class);
                    assertThat(context.getBean(OtlpConfig.class).headers())
                            .as("the header map key must bind as 'authorization', or every push answers 401")
                            .containsEntry("authorization", EXPECTED_HEADER);
                    assertThat(context.getBean(SslBundles.class).getBundle(OtlpPushEnvironmentPostProcessor.BUNDLE)
                            .getStores().getTrustStore().aliases().hasMoreElements())
                            .as("the bundle holds the VM's CA")
                            .isTrue();
                });
    }

    @Test
    @DisplayName("Spring Boot finds the post-processor, so a deployed app really derives the header")
    void isRegistered() {
        assertThat(org.springframework.core.io.support.SpringFactoriesLoader.forDefaultResourceLocation()
                .load(org.springframework.boot.EnvironmentPostProcessor.class, null,
                        org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler
                                .handleMessage((message, failure) -> { })))
                .as("a typo in META-INF/spring.factories leaves every push unauthenticated, and nothing fails until Azure")
                .anyMatch(OtlpPushEnvironmentPostProcessor.class::isInstance);
    }

    @Test
    @DisplayName("the CA is trusted for the push alone, never imported into the JVM's trust store")
    void theCaIsNotTrustedProcessWide() throws IOException {
        Path sources = Path.of("src", "main", "java");
        try (var files = Files.walk(sources)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    assertThat(Files.readString(path))
                            .as("%s touches the JVM trust store; a compromised VM would then be trusted for "
                                    + "Key Vault, ACS and Postgres too", path)
                            .doesNotContain("javax.net.ssl.trustStore");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    private static String testCa() throws IOException {
        try (InputStream pem = OtlpPushEnvironmentPostProcessorTest.class.getResourceAsStream("/otlp/test-ca.pem")) {
            assertThat(pem).isNotNull();
            return new String(pem.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
