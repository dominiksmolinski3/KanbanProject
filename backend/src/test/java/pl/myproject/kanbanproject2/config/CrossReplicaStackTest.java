package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard over the one claim this repository could not make until there were two API replicas to
 * make it about: that a board event published by one of them reaches a browser whose WebSocket is
 * held by another.
 *
 * {@code cross-replica-sync.cy.js} is the spec, and three separate things have to stay true for it
 * to mean anything - none of which any compiler, linter or runtime can see:
 *
 * <ul>
 *   <li><b>Two replicas have to exist.</b> {@code docker-compose.yml}'s {@code app2} service is
 *       the second one, and it is behind the {@code replicas} profile so the ordinary stack is
 *       unchanged.</li>
 *   <li><b>The spec has to address the replica the browser is <i>not</i> on.</b> nginx proxies to
 *       {@code app}, which publishes 8081; {@code app2} publishes 8082, one digit away. If those
 *       two ever name the same port the spec still passes - faster - while proving exactly what
 *       {@code live-sync.cy.js} already proves. That is the failure this class exists for, and
 *       nothing at runtime can distinguish it from success.</li>
 *   <li><b>Something has to run it.</b> The spec is deliberately outside Cypress's default
 *       {@code specPattern}, so the ordinary suite does not fail for everybody running a
 *       single-replica stack. The price of that is a spec nothing runs by default, and the only
 *       thing that runs it is a named step in {@code kanban-ci.yml}'s e2e job.</li>
 * </ul>
 *
 * Same shape as {@link SweepAlarmCoverageTest} and {@code DeadLetterAlertTest}: a rule that has to
 * live in several files, checked in one.
 */
class CrossReplicaStackTest {

    /** Tests run with {@code backend/} as the working directory, so the repository root is up one. */
    private static final Path REPO = Path.of("..");

    private static final Path COMPOSE = REPO.resolve("docker-compose.yml");
    private static final Path CI = REPO.resolve(Path.of(".github", "workflows", "kanban-ci.yml"));
    private static final Path PACKAGE_JSON = REPO.resolve(Path.of("frontend", "package.json"));
    private static final Path CYPRESS_CONFIG = REPO.resolve(Path.of("frontend", "cypress.config.js"));
    private static final Path SPEC =
            REPO.resolve(Path.of("frontend", "cypress", "replicas", "cross-replica-sync.cy.js"));

    /** The npm script that is the only thing that runs the spec. */
    private static final String SCRIPT = "cypress:run:replicas";

    /** The compose profile the second replica sits behind. */
    private static final String PROFILE = "replicas";

    @Test
    @DisplayName("the compose stack can run a second API replica, and it is the same application")
    void composeDeclaresASecondApiReplica() throws IOException {
        Map<String, Object> app = service("app");
        Map<String, Object> second = service("app2");

        assertThat(second.get("profiles"))
                .as("app2 must stay behind the `%s` profile: it is a second JVM, and `docker "
                        + "compose up -d` should give the same stack it always did", PROFILE)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(PROFILE);

        assertThat(second.get("environment"))
                .as("the two replicas' environments have drifted apart, which makes them two "
                        + "different applications - they are meant to share one anchor, because "
                        + "the whole point of the second one is that it is the same one")
                .isEqualTo(app.get("environment"));

        assertThat(second.get("build"))
                .as("app2 no longer builds the same image as app")
                .isEqualTo(app.get("build"));
    }

    @Test
    @DisplayName("the spec writes to the replica's own port, not to the one nginx proxies to")
    void theSpecAddressesTheOtherReplica() throws IOException {
        String first = publishedPort(service("app"));
        String second = publishedPort(service("app2"));

        assertThat(second)
                .as("app and app2 publish the same host port, so the spec's second address is the "
                        + "replica holding the browser's socket and the cross-replica claim is no "
                        + "longer being tested at all")
                .isNotEqualTo(first);

        String spec = read(SPEC);

        assertThat(spec)
                .as("the spec's default second-replica address does not name app2's published "
                        + "port (%s); it would be writing to whatever answers there instead", second)
                .contains("http://127.0.0.1:" + second);

        assertThat(spec)
                .as("the spec's default address names app's own published port (%s), which is the "
                        + "replica nginx proxies to - the write and the subscription would be on "
                        + "one process and the spec would pass having crossed nothing", first)
                .doesNotContain("http://127.0.0.1:" + first);
    }

    @Test
    @DisplayName("the spec is outside the default spec pattern, and something else runs it")
    void somethingRunsTheSpec() throws IOException {
        assertThat(SPEC).as("the cross-replica spec has moved or gone").isRegularFile();

        assertThat(read(CYPRESS_CONFIG))
                .as("cypress.config.js no longer pins the default spec pattern, so whether the "
                        + "ordinary suite picks up a spec needing two replicas is now a Cypress "
                        + "default rather than a decision")
                .contains("specPattern: 'cypress/e2e/**/*.cy.{js,jsx,ts,tsx}'");

        assertThat(read(PACKAGE_JSON))
                .as("`npm run %s` is the only thing that runs the spec", SCRIPT)
                .contains("\"" + SCRIPT + "\"")
                .contains("cypress/replicas/");

        String ci = read(CI);

        assertThat(ci)
                .as("CI brings the stack up without the `%s` profile, so there is no second "
                        + "replica for the spec to address", PROFILE)
                .contains("docker compose --profile " + PROFILE + " up -d --build");

        assertThat(ci)
                .as("nothing in CI runs `npm run %s`, so the cross-replica claim is asserted by a "
                        + "spec that never executes - which is the same as not asserting it, and "
                        + "reads as success", SCRIPT)
                .contains("npm run " + SCRIPT);
    }

    /** The {@code services:} entry, with the shared anchor already merged in by the YAML parser. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) throws IOException {
        assertThat(COMPOSE).as("the compose file has moved or gone").isRegularFile();

        Map<String, Object> compose;
        try (var yaml = Files.newBufferedReader(COMPOSE, StandardCharsets.UTF_8)) {
            compose = new Yaml().load(yaml);
        }

        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        assertThat(services).as("the compose file declares no services").isNotNull();

        Map<String, Object> service = (Map<String, Object>) services.get(name);
        assertThat(service).as("the compose file no longer has a `%s` service", name).isNotNull();
        return service;
    }

    /** The host port of the service's single published mapping, {@code "127.0.0.1:8081:8080"}. */
    @SuppressWarnings("unchecked")
    private static String publishedPort(Map<String, Object> service) {
        List<String> ports = (List<String>) service.get("ports");
        assertThat(ports)
                .as("the service publishes no port, so nothing outside the compose network can "
                        + "address it")
                .isNotNull()
                .hasSize(1);

        String[] parts = ports.get(0).split(":");
        assertThat(parts)
                .as("unexpected port mapping `%s`", ports.get(0))
                .hasSizeGreaterThanOrEqualTo(2);
        return parts[parts.length - 2];
    }

    private static String read(Path path) throws IOException {
        assertThat(path).as("%s has moved or gone", path).isRegularFile();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
