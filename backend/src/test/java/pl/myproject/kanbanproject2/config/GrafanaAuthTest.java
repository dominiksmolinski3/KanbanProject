package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GrafanaAuthTest {
    private static final Path REPO = Path.of("..");
    private static final Path MODULE = REPO.resolve(Path.of("terraform", "modules", "grafana_app", "main.tf"));
    private static final Path IMAGE = REPO.resolve(Path.of("observability", "grafana", "Dockerfile"));
    private static final Path DATASOURCES = REPO.resolve(Path.of("observability", "grafana", "azure", "datasources.yml"));

    @Test
    @DisplayName("only users assigned a Grafana role can get a token for it")
    void assignmentIsRequired() throws IOException {
        assertThat(read(MODULE))
                .as("without it any account in the directory signs in at the ingress and reaches Grafana's code")
                .containsPattern("app_role_assignment_required\\s*=\\s*true");
    }

    @Test
    @DisplayName("the ingress turns away anyone who has not signed in")
    void theIngressRequiresSignIn() throws IOException {
        assertThat(read(MODULE))
                .containsPattern("type\\s*=\\s*\"Microsoft\\.App/containerApps/authConfigs@")
                .containsPattern("unauthenticatedClientAction\\s*=\\s*\"RedirectToLoginPage\"")
                .doesNotContain("AllowAnonymous");
    }

    @Test
    @DisplayName("Grafana refuses a token that carries none of its roles")
    void grafanaMapsRolesStrictly() throws IOException {
        assertThat(read(MODULE))
                .as("without strict mapping Grafana would sign such a user in as a Viewer")
                .containsPattern("GF_AUTH_AZUREAD_ROLE_ATTRIBUTE_STRICT\"\\s*\\n\\s*value\\s*=\\s*\"true\"");
    }

    @Test
    @DisplayName("there is no client secret, and no way in that bypasses Entra")
    void noSecretAndNoLocalLogin() throws IOException {
        assertThat(read(MODULE))
                .as("the app signs in with its managed identity through a federated credential")
                .doesNotContain("azuread_application_password")
                .contains("GF_AUTH_AZUREAD_CLIENT_AUTHENTICATION");
        assertThat(read(IMAGE))
                .contains("GF_AUTH_ANONYMOUS_ENABLED=false")
                .contains("GF_AUTH_DISABLE_LOGIN_FORM=true");
    }

    @Test
    @DisplayName("Grafana reads Prometheus with the read credential, never the push one")
    void grafanaUsesTheReadCredential() throws IOException {
        assertThat(read(DATASOURCES))
                .contains("basicAuthUser: grafana")
                .doesNotContain("kanban-api");
        assertThat(read(MODULE))
                .contains("read_password_secret_name")
                .doesNotContain("MONITORING-PUSH-PASSWORD");
    }

    private static String read(Path path) throws IOException {
        assertThat(path).exists();
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
