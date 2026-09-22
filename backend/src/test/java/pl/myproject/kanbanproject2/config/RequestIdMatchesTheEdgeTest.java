package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request id is a rule in two files and neither compiles the other.
 *
 * <p>nginx mints it and forwards it; {@link RequestIdFilter} reads it. If the header name moves on
 * one side, nothing fails - the edge logs an id, the application logs a different one, and the two
 * streams look exactly as joined as they did before while being joined by nothing. That is the
 * failure this whole change exists to remove, so it is worth a test of the shape
 * {@code SecurityHeadersMatchTheEdgeTest} already uses against the headers snippet.
 *
 * <p>The assertion about <em>every</em> proxying location is the one that earns its keep over time.
 * The three that exist today all forward the header; a fourth added later without it is a route
 * whose application lines carry an id the edge never logged, which is a blind spot in exactly the
 * place a new route is most likely to need one.
 */
class RequestIdMatchesTheEdgeTest {

    private static final Path REPO = Path.of("..");
    private static final Path EDGE_TEMPLATE = REPO.resolve(Path.of("frontend", "nginx", "default.conf.template"));
    private static final Path APP_PROPERTIES = Path.of("src", "main", "resources", "application.properties");
    private static final Path COMPOSE = REPO.resolve("docker-compose.yml");
    private static final Path API_APP = REPO.resolve(Path.of("terraform", "modules", "api_app", "main.tf"));

    /** {@code location <match> {} ... }, captured with its body so each one can be asked about itself. */
    private static final Pattern LOCATION = Pattern.compile("\\n    location ([^{]+)\\{(.*?)\\n    }", Pattern.DOTALL);

    @Test
    @DisplayName("the edge forwards the header the filter reads")
    void theEdgeAndTheFilterNameTheSameHeader() {
        assertThat(edgeTemplate())
                .as("the filter reads %s and the edge template never mentions it, so the id in the "
                        + "application's logs is one nginx has never seen", RequestIdFilter.HEADER)
                .contains("proxy_set_header " + RequestIdFilter.HEADER);
    }

    @Test
    @DisplayName("every location that proxies forwards the request id")
    void everyProxiedLocationForwardsIt() {
        List<String> withoutIt = new ArrayList<>();
        int proxying = 0;

        Matcher locations = LOCATION.matcher(edgeTemplate());
        while (locations.find()) {
            String match = locations.group(1).trim();
            String body = locations.group(2);
            if (!body.contains("proxy_pass")) {
                continue;
            }
            proxying++;
            if (!body.contains("proxy_set_header " + RequestIdFilter.HEADER)) {
                withoutIt.add(match);
            }
        }

        assertThat(proxying)
                .as("no proxying locations were found in the edge template, which means this test "
                        + "has stopped parsing it rather than that the edge proxies nothing")
                .isGreaterThanOrEqualTo(3);

        assertThat(withoutIt)
                .as("these locations proxy to the API and do not pass %s, so their application log "
                        + "lines carry an id the edge never logged", RequestIdFilter.HEADER)
                .isEmpty();
    }

    @Test
    @DisplayName("the edge's own access log carries the id it forwards")
    void theEdgeLogsTheIdItSends() {
        // Forwarding it without logging it joins the application to nothing: the edge line is the
        // half that says whether the request reached the API at all.
        assertThat(edgeTemplate())
                .as("the log_format does not carry $edge_request_id, so the 502 recorded here still "
                        + "cannot be matched to anything")
                .containsPattern("log_format\\s+edge[^;]*\\$edge_request_id");

        assertThat(edgeTemplate())
                .as("the `edge` log_format is defined and nothing selects it, so nginx goes on "
                        + "writing the stock `main` format")
                .containsPattern("access_log\\s+\\S+\\s+edge;");
    }

    @Test
    @DisplayName("an inbound id is bounded on both sides of the hop")
    void bothSidesRefuseAnUnboundedId() {
        // Whatever arrives ends up in every log line for the request. The API container is also
        // reachable without going through nginx, so one check would leave the other door open.
        assertThat(edgeTemplate())
                .as("the edge's map trusts $http_x_request_id unconditionally")
                .containsPattern("\\[A-Za-z0-9_-]\\{8,64}");
    }

    @Test
    @DisplayName("structured logging is off by default and on wherever a machine reads the logs")
    void theMachineReadablePlacesAreConfiguredForIt() {
        assertThat(read(APP_PROPERTIES))
                .as("a default of anything but empty makes a unit test's own output JSON")
                .contains("logging.structured.format.console=${LOG_FORMAT:}");

        // A requestId in the MDC and prose on the console is the finding rather than the fix: the
        // field has to reach Log Analytics as a field to be queryable next to the edge's rid=.
        assertThat(read(COMPOSE))
                .as("the local stack logs prose, so nothing here exercises the format the "
                        + "deployment collects")
                .containsPattern("LOG_FORMAT:\\s*ecs");

        assertThat(read(API_APP))
                .as("the container app does not set LOG_FORMAT, so Log Analytics receives prose "
                        + "and the requestId is a substring rather than a field")
                .containsPattern("\"LOG_FORMAT\"\\s*\\n\\s*value\\s*=\\s*\"ecs\"");
    }

    private static String edgeTemplate() {
        return read(EDGE_TEMPLATE);
    }

    private static String read(Path path) {
        try {
            assertThat(path).as("%s has moved or gone", path).isRegularFile();
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path.toAbsolutePath(), e);
        }
    }
}
