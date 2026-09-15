package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.yaml.snakeyaml.Yaml;
import pl.myproject.kanbanproject2.config.security.captcha.CaptchaProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitProperties;

import java.io.IOException;
import java.io.InputStream;
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

/**
 * A guard over the environment variables this application reads and the three places that are
 * supposed to supply them.
 *
 * <p>Configuration is the one kind of coupling nothing else here can see. A property the
 * application requires and no environment sets is a container that will not start; a secret an
 * environment supplies and nothing reads is a lie that survives every build, every test and every
 * review, because there is no compiler on either side of the gap. This project has had that happen
 * twice: {@code SPRING-MAIL-USERNAME} and {@code SPRING-MAIL-PASSWORD} sat in Key Vault for two
 * revisions after the application stopped reading either, which is the drift half of MAIL-02, and
 * {@code CAPTCHA_SECRET} was plumbed through docker-compose, Terraform, Key Vault and the container
 * template to a verifier that did not exist (SEC-06). Both were found by hand, one round late.
 *
 * <p>Six sources are read and compared:
 *
 * <ul>
 *   <li>{@code application.properties} - every {@code ${VAR}} placeholder, and whether it carries
 *       a default. No default means the application cannot start without it.</li>
 *   <li>The {@code @ConfigurationProperties} records - a variable can be bound through relaxed
 *       binding without ever appearing in {@code application.properties}, which is exactly how
 *       {@code SECURITY_RATE_LIMIT_TRUSTED_PROXY_COUNT} reaches
 *       {@link AuthRateLimitProperties#trustedProxyCount()}. Leave those out and the audit reports
 *       live configuration as dead.</li>
 *   <li>{@code docker-compose.yml} - what the local stack passes each container.</li>
 *   <li>{@code terraform/modules/api_app/main.tf} - what the deployment passes the API.</li>
 *   <li>{@code terraform/modules/web_app/main.tf} and
 *       {@code frontend/nginx/default.conf.template} - the same audit for the edge, which the
 *       split added a second container's worth of. Its configuration is one variable, which is
 *       exactly why it needs checking: a bidirectional audit that does not know the module exists
 *       is how a variable ends up passed and unread, and this one is not read by any Java at
 *       all.</li>
 * </ul>
 *
 * <p>Same shape as {@code DeadLetterAlertTest} and {@code SupportedLocalesMatchClientTest}: a rule
 * that lives in several files, checked in one, needing no database and no running container. None of
 * these tests skips when a file it reads is missing, because a guard that turns itself off leaves
 * the build green either way and only one of those two states is honest.
 */
class ConfigurationTest {

    /** Tests run with {@code backend/} as the working directory, so the repository root is up one. */
    private static final Path REPO = Path.of("..");
    private static final Path APP_PROPERTIES = Path.of("src", "main", "resources", "application.properties");
    private static final Path COMPOSE = REPO.resolve("docker-compose.yml");
    private static final Path ENV_EXAMPLE = REPO.resolve(".env.example");
    private static final Path API_APP = REPO.resolve(Path.of("terraform", "modules", "api_app", "main.tf"));
    private static final Path WEB_APP = REPO.resolve(Path.of("terraform", "modules", "web_app", "main.tf"));
    private static final Path EDGE_TEMPLATE = REPO.resolve(Path.of("frontend", "nginx", "default.conf.template"));
    private static final Path EDGE_DOCKERFILE = REPO.resolve(Path.of("frontend", "Dockerfile"));

    /**
     * The one placeholder in the edge template that no deployment supplies, because the image does.
     *
     * <p>{@code NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1} in {@code frontend/Dockerfile} makes the stock
     * nginx entrypoint read {@code /etc/resolv.conf} and export this before {@code envsubst} runs,
     * which is what keeps the resolver address out of this repository - it differs between Docker
     * and Container Apps in principle even though both answer {@code 127.0.0.11} today. Drop that
     * line from the Dockerfile and the substitution produces {@code resolver ;}, which nginx
     * refuses to start on, so {@link #theEdgeImageEnablesTheResolverEntrypoint()} asserts it.
     */
    private static final Set<String> EDGE_ENTRYPOINT_PROVIDED = Set.of("NGINX_LOCAL_RESOLVERS");

    /**
     * The records Spring binds, named rather than discovered by scanning.
     *
     * <p>Renaming or deleting one of these should be a compile error here and not a quietly smaller
     * audit - a classpath scan that finds four classes today and three tomorrow reports the missing
     * one's variables as unread, which is the opposite of what this test is for.
     */
    private static final List<Class<?>> BOUND_PROPERTIES = List.of(
            AcsMailProperties.class,
            AllowedOriginsProperties.class,
            CaptchaProperties.class,
            AuthRateLimitProperties.class);

    /** {@code ${VAR}} or {@code ${VAR:default}} - the default may be empty, which still counts as one. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)(:[^}]*)?}");

    /** An {@code env { ... }} block in a container-app module. These never nest, so this is enough. */
    private static final Pattern TERRAFORM_ENV_BLOCK = Pattern.compile("\\benv\\s*\\{([^}]*)}");
    private static final Pattern TERRAFORM_ENV_NAME = Pattern.compile("name\\s*=\\s*\"([A-Z][A-Z0-9_]*)\"");

    // ---------------------------------------------------------------- what the application reads

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

    // ---------------------------------------------------------------- what the environments supply

    @Test
    @DisplayName("the container app passes nothing the application does not read")
    void theContainerAppPassesNothingUnread() throws IOException {
        Set<String> unread = new TreeSet<>(terraformContainerAppEnvironment());
        unread.removeAll(readable());

        assertThat(unread)
                .as("Terraform supplies these and no property binds them - this is the shape MAIL-02 had")
                .isEmpty();
    }

    // ------------------------------------------------------------------------------- the edge
    //
    // The same audit, for the container with no Java in it. There is one variable, and one variable
    // is the case this is most needed for rather than least: nothing here is bound by a record, so
    // a rename on either side compiles, applies, and produces a 502 on every API call.

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

        assertThat(unread)
                .as("the local stack sets these on the app container and nothing reads them at runtime")
                .isEmpty();
    }

    // ---------------------------------------------------------------- the template

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

    // ---------------------------------------------------------------- sources

    /**
     * Every environment variable the application can read: the placeholders in
     * {@code application.properties}, plus the relaxed-binding name of every component of every
     * bound {@code @ConfigurationProperties} record.
     */
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

    /** The placeholders with no default - the application refuses to start without these. */
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

    /** Placeholder name to whether it carries a default. */
    private static Map<String, Boolean> placeholders() throws IOException {
        Map<String, Boolean> placeholders = new LinkedHashMap<>();
        Matcher matcher = PLACEHOLDER.matcher(read(APP_PROPERTIES));
        while (matcher.find()) {
            // A name used twice, once with a default and once without, is required.
            placeholders.merge(matcher.group(1), matcher.group(2) != null, (a, b) -> a && b);
        }
        return placeholders;
    }

    /**
     * Spring's relaxed binding, in the direction this test needs it:
     * {@code security.rate-limit.trustedProxyCount} to {@code SECURITY_RATE_LIMIT_TRUSTED_PROXY_COUNT}.
     */
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

    /** The {@code environment:} mapping on the compose file's {@code app} service. */
    private static Map<String, String> composeAppEnvironment() throws IOException {
        return composeServiceEnvironment("app");
    }

    /** The same, for the {@code web} service the split added. */
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
            // The other form the compose spec allows: a list of `KEY=value` strings.
            for (Object entry : entries) {
                String[] halves = String.valueOf(entry).split("=", 2);
                variables.put(halves[0], halves.length > 1 ? halves[1] : "");
            }
        }
        return variables;
    }

    /** Every {@code ${VAR}} anywhere in the compose file, environment and build args alike. */
    private static Set<String> interpolatedByCompose() throws IOException {
        Set<String> interpolated = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(read(COMPOSE));
        while (matcher.find()) {
            interpolated.add(matcher.group(1));
        }
        return interpolated;
    }

    /** The names in the API module's {@code env} blocks, and not its Key Vault secret names. */
    private static Set<String> terraformContainerAppEnvironment() throws IOException {
        return terraformEnvironment(API_APP, "the API module");
    }

    /** The same, for the edge. One entry today, and the point is that it is checked at all. */
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

    /** Every placeholder the nginx entrypoint's envsubst will replace in the edge config. */
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

    /** The keys {@code .env.example} asks somebody to fill in, comments and blanks aside. */
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
