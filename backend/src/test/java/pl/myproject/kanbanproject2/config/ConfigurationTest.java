package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.yaml.snakeyaml.Yaml;
import pl.myproject.kanbanproject2.config.security.captcha.CaptchaProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitProperties;
import pl.myproject.kanbanproject2.config.websocket.StompRelayProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationTest {
    private static final Path REPO = Path.of("..");
    private static final Path APP_PROPERTIES = Path.of("src", "main", "resources", "application.properties");
    private static final Path COMPOSE = REPO.resolve("docker-compose.yml");
    private static final Path ENV_EXAMPLE = REPO.resolve(".env.example");
    private static final Path API_APP = REPO.resolve(Path.of("terraform", "modules", "api_app", "main.tf"));
    private static final Path WEB_APP = REPO.resolve(Path.of("terraform", "modules", "web_app", "main.tf"));
    private static final Path EDGE_TEMPLATE = REPO.resolve(Path.of("frontend", "nginx", "default.conf.template"));
    private static final Path EDGE_DOCKERFILE = REPO.resolve(Path.of("frontend", "Dockerfile"));

    private static final Set<String> EDGE_ENTRYPOINT_PROVIDED = Set.of("NGINX_LOCAL_RESOLVERS");

    private static final Set<String> AGENT_READ = Set.of(
            "APPLICATIONINSIGHTS_CONNECTION_STRING",
            "APPLICATIONINSIGHTS_AUTHENTICATION_STRING",
            "APPLICATIONINSIGHTS_METRIC_INTERVAL_SECONDS");

    private static final Path API_DOCKERFILE = Path.of("Dockerfile");

    private static final List<Class<?>> BOUND_PROPERTIES = List.of(
            AcsMailProperties.class,
            AllowedOriginsProperties.class,
            CaptchaProperties.class,
            AuthRateLimitProperties.class,
            StompRelayProperties.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(:[^}]*)?}");

    private static final Pattern TERRAFORM_ENV_BLOCK = Pattern.compile("\\benv\\s*\\{([^}]*)}");
    private static final Pattern TERRAFORM_ENV_NAME = Pattern.compile("name\\s*=\\s*\"([A-Z][A-Z0-9_]*)\"");

    @Test
    @DisplayName("every variable the application cannot start without is supplied by docker-compose")
    void dockerComposeSuppliesEveryRequiredVariable() throws IOException {
        assertThat(required())
                .as("a variable with no default that the local stack never sets - the container will not start")
                .isSubsetOf(composeAppEnvironment().keySet());
    }

    @Test
    @DisplayName("every variable the application cannot start without is supplied by the container app")
    void theContainerAppSuppliesEveryRequiredVariable() throws IOException {
        assertThat(required())
                .as("a variable with no default that Terraform never sets - the revision would never go healthy")
                .isSubsetOf(terraformContainerAppEnvironment());
    }

    @Test
    @DisplayName("the container app passes nothing the application does not read")
    void theContainerAppPassesNothingUnread() throws IOException {
        Set<String> unread = new TreeSet<>(terraformContainerAppEnvironment());
        unread.removeAll(readable());
        unread.removeAll(AGENT_READ);

        assertThat(unread)
                .as("Terraform supplies these and no property binds them - this is the shape MAIL-02 had")
                .isEmpty();
    }

    @Test
    @DisplayName("the API image attaches the agent its variables are for, keyed on the one Terraform sets")
    void theApiImageAttachesTheAgentItsVariablesAreFor() throws IOException {
        String dockerfile = Files.readString(API_DOCKERFILE, StandardCharsets.UTF_8);

        assertThat(dockerfile)
                .as("the backend image no longer carries the agent, so %s are read by nothing", AGENT_READ)
                .contains("applicationinsights-agent.jar")
                .contains("-javaagent:")
                .contains("APPLICATIONINSIGHTS_CONNECTION_STRING");
        assertThat(terraformContainerAppEnvironment())
                .as("the container app no longer sets what attaches the agent, so no metric reaches an alert")
                .contains("APPLICATIONINSIGHTS_CONNECTION_STRING", "APPLICATIONINSIGHTS_AUTHENTICATION_STRING");
    }

    @Test
    @DisplayName("the web module supplies every variable the edge template substitutes")
    void theWebModuleSuppliesEveryEdgeVariable() throws IOException {
        Set<String> needed = new TreeSet<>(edgeTemplatePlaceholders());
        needed.removeAll(EDGE_ENTRYPOINT_PROVIDED);

        assertThat(needed)
                .as("envsubst leaves an unset placeholder empty rather than failing, so a variable "
                        + "the template needs and Terraform does not pass renders a directive with "
                        + "a hole in it")
                .isSubsetOf(terraformWebAppEnvironment());
    }

    @Test
    @DisplayName("the web module passes nothing the edge template does not substitute")
    void theWebModulePassesNothingUnread() throws IOException {
        Set<String> unread = new TreeSet<>(terraformWebAppEnvironment());
        unread.removeAll(edgeTemplatePlaceholders());

        assertThat(unread)
                .as("Terraform passes these to nginx and its config names none of them")
                .isEmpty();
    }

    @Test
    @DisplayName("docker-compose supplies the edge the same variables the deployment does")
    void dockerComposeSuppliesTheEdge() throws IOException {
        Set<String> needed = new TreeSet<>(edgeTemplatePlaceholders());
        needed.removeAll(EDGE_ENTRYPOINT_PROVIDED);

        assertThat(needed)
                .as("the local stack is the only place an nginx misconfiguration gets caught "
                        + "before it reaches Azure, which it cannot do while it is configured "
                        + "differently")
                .isSubsetOf(composeWebEnvironment().keySet());
    }

    @Test
    @DisplayName("the edge image enables the entrypoint that fills in the resolver")
    void theEdgeImageEnablesTheResolverEntrypoint() throws IOException {
        assertThat(read(EDGE_DOCKERFILE))
                .as("without NGINX_ENTRYPOINT_LOCAL_RESOLVERS the stock entrypoint exports no "
                        + "NGINX_LOCAL_RESOLVERS, envsubst renders an empty resolver directive, and "
                        + "nginx refuses to start - which is at least loud, unlike everything else "
                        + "on this page")
                .contains("NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1");
    }

    @Test
    @DisplayName("docker-compose passes the app nothing the application does not read")
    void dockerComposePassesNothingUnread() throws IOException {
        Set<String> unread = new TreeSet<>(composeAppEnvironment().keySet());
        unread.removeAll(readable());
        unread.removeAll(AGENT_READ);

        assertThat(unread)
                .as("the local stack sets these on the app container and nothing reads them at runtime")
                .isEmpty();
    }

    @Test
    @DisplayName(".env.example names every variable docker-compose interpolates")
    void theTemplateNamesEveryVariableComposeNeeds() throws IOException {
        assertThat(interpolatedByCompose())
                .as("docker-compose reads these from .env and the template does not mention them, "
                        + "so a fresh clone that copies the template cannot bring the stack up")
                .isSubsetOf(templateKeys());
    }

    @Test
    @DisplayName(".env.example names nothing that neither the application nor docker-compose uses")
    void theTemplateNamesNothingUnused() throws IOException {
        Set<String> unused = new TreeSet<>(templateKeys());
        unused.removeAll(readable());
        unused.removeAll(interpolatedByCompose());

        assertThat(unused)
                .as("the template asks somebody to fill these in and nothing anywhere reads them")
                .isEmpty();
    }

    private static Set<String> readable() throws IOException {
        Set<String> readable = new TreeSet<>(placeholders().keySet());
        for (Class<?> type : BOUND_PROPERTIES) {
            String prefix = type.getAnnotation(ConfigurationProperties.class).prefix();
            assertThat(prefix)
                    .as("%s is in the audit list without a prefix to bind from", type.getSimpleName())
                    .isNotBlank();
            for (RecordComponent component : type.getRecordComponents()) {
                readable.add(environmentNameOf(prefix + "." + component.getName()));
            }
        }
        return readable;
    }

    private static Set<String> required() throws IOException {
        Set<String> required = new TreeSet<>();
        placeholders().forEach((name, hasDefault) -> {
            if (!hasDefault) {
                required.add(name);
            }
        });
        assertThat(required)
                .as("application.properties suddenly has no required variable at all, "
                        + "which means the placeholder pattern has stopped matching rather than that the app got simpler")
                .isNotEmpty();
        return required;
    }

    private static Map<String, Boolean> placeholders() throws IOException {
        Map<String, Boolean> placeholders = new LinkedHashMap<>();
        Matcher matcher = PLACEHOLDER.matcher(read(APP_PROPERTIES));
        while (matcher.find()) {
            placeholders.merge(matcher.group(1), matcher.group(2) != null, (a, b) -> a && b);
        }
        return placeholders;
    }

    private static String environmentNameOf(String property) {
        StringBuilder name = new StringBuilder(property.length() + 8);
        for (char character : property.toCharArray()) {
            if (character == '.' || character == '-') {
                name.append('_');
            } else if (Character.isUpperCase(character)) {
                name.append('_').append(character);
            } else {
                name.append(Character.toUpperCase(character));
            }
        }
        return name.toString();
    }

    private static Map<String, String> composeAppEnvironment() throws IOException {
        return composeServiceEnvironment("app");
    }

    private static Map<String, String> composeWebEnvironment() throws IOException {
        return composeServiceEnvironment("web");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> composeServiceEnvironment(String service) throws IOException {
        assertThat(COMPOSE).as("the compose file has moved or gone").isRegularFile();

        Map<String, Object> compose;
        try (InputStream yaml = Files.newInputStream(COMPOSE)) {
            compose = new Yaml().load(yaml);
        }

        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        assertThat(services).as("the compose file declares no services").isNotNull();
        Map<String, Object> app = (Map<String, Object>) services.get(service);
        assertThat(app).as("the compose file no longer has a `%s` service", service).isNotNull();

        Object environment = app.get("environment");
        assertThat(environment).as("the `%s` service passes no environment at all", service).isNotNull();

        Map<String, String> variables = new LinkedHashMap<>();
        if (environment instanceof Map<?, ?> mapping) {
            mapping.forEach((key, value) -> variables.put(String.valueOf(key), String.valueOf(value)));
        } else if (environment instanceof List<?> entries) {
            for (Object entry : entries) {
                String[] halves = String.valueOf(entry).split("=", 2);
                variables.put(halves[0], halves.length > 1 ? halves[1] : "");
            }
        }
        return variables;
    }

    private static Set<String> interpolatedByCompose() throws IOException {
        Set<String> interpolated = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(read(COMPOSE));
        while (matcher.find()) {
            interpolated.add(matcher.group(1));
        }
        return interpolated;
    }

    private static Set<String> terraformContainerAppEnvironment() throws IOException {
        return terraformEnvironment(API_APP, "the API module");
    }

    private static Set<String> terraformWebAppEnvironment() throws IOException {
        return terraformEnvironment(WEB_APP, "the web module");
    }

    private static Set<String> terraformEnvironment(Path module, String what) throws IOException {
        assertThat(module).as("%s has moved or gone", what).isRegularFile();

        Set<String> names = new TreeSet<>();
        Matcher blocks = TERRAFORM_ENV_BLOCK.matcher(read(module));
        while (blocks.find()) {
            Matcher name = TERRAFORM_ENV_NAME.matcher(blocks.group(1));
            if (name.find()) {
                names.add(name.group(1));
            }
        }

        assertThat(names)
                .as("no env blocks were found in %s, which means this test is reading the wrong "
                        + "file or the wrong shape rather than that the container needs no configuration", what)
                .isNotEmpty();
        return names;
    }

    private static Set<String> edgeTemplatePlaceholders() throws IOException {
        Set<String> names = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(read(EDGE_TEMPLATE));
        while (matcher.find()) {
            names.add(matcher.group(1));
        }

        assertThat(names)
                .as("the edge template substitutes nothing at all, which means this is reading the "
                        + "wrong file rather than that the edge needs no configuration")
                .isNotEmpty();
        return names;
    }

    private static Set<String> templateKeys() throws IOException {
        assertThat(ENV_EXAMPLE).as("the env template has moved or gone").isRegularFile();

        Set<String> keys = new TreeSet<>();
        for (String line : Files.readAllLines(ENV_EXAMPLE)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (equals > 0) {
                keys.add(trimmed.substring(0, equals).trim());
            }
        }
        return keys;
    }

    private static String read(Path path) throws IOException {
        assertThat(path).as("%s has moved or gone", path).isRegularFile();
        return Files.readString(path);
    }
}
