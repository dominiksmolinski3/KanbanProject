package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeProxyPathsTest {
    private static final Path ROLES = Path.of("..", "ansible", "roles");
    private static final Path PROXY = ROLES.resolve(Path.of("edge_proxy", "templates", "prometheus.conf.j2"));
    private static final Path PROMETHEUS_UNIT = ROLES.resolve(Path.of("prometheus_stack", "templates", "prometheus.service.j2"));

    private static final String OTLP_LOCATION = "= /api/v1/otlp/v1/metrics";

    private static final Pattern LOCATION = Pattern.compile("location\\s+([^{]+?)\\s*\\{(.*?)\\n    }", Pattern.DOTALL);

    @Test
    @DisplayName("the push credential guards the OTLP path and nothing else")
    void thePushCredentialOnlyPushes() throws IOException {
        Map<String, String> locations = locations();

        assertThat(locations.entrySet())
                .as("a second location behind htpasswd.push hands the API's credential a second purpose")
                .filteredOn(location -> location.getValue().contains("htpasswd.push"))
                .singleElement()
                .satisfies(location -> {
                    assertThat(location.getKey()).isEqualTo(OTLP_LOCATION);
                    assertThat(location.getValue()).containsPattern("limit_except\\s+POST\\s*\\{");
                });
    }

    @Test
    @DisplayName("every location that reaches Prometheus asks for a credential")
    void everyProxiedLocationAuthenticates() throws IOException {
        assertThat(locations().values())
                .filteredOn(body -> body.contains("proxy_pass"))
                .isNotEmpty()
                .allMatch(body -> body.contains("auth_basic_user_file"));
    }

    @Test
    @DisplayName("the read credential reaches query paths, never the admin API or the push")
    void theReadPathsAreQueriesOnly() throws IOException {
        Pattern read = readPaths();

        assertThat(read.matcher("/api/v1/query").matches()).isTrue();
        assertThat(read.matcher("/api/v1/label/__name__/values").matches()).isTrue();
        for (String refused : new String[] {
                "/api/v1/admin/tsdb/snapshot", "/api/v1/admin/tsdb/delete_series", "/api/v1/otlp/v1/metrics",
                "/api/v1/targets", "/api/v1/status/config", "/-/reload", "/-/quit", "/api/v1/write"}) {
            assertThat(read.matcher(refused).matches())
                    .as("%s would be reachable with the read credential", refused)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("anything not listed answers 404")
    void theRestIsRefused() throws IOException {
        assertThat(locations()).containsKey("/");
        assertThat(locations().get("/")).contains("return 404");
    }

    @Test
    @DisplayName("Prometheus listens on loopback, so the proxy is the only way in")
    void prometheusIsNotReachableAroundTheProxy() throws IOException {
        assertThat(read(PROMETHEUS_UNIT))
                .as("Prometheus has no authentication of its own; on any other address the admin API is open")
                .contains("--web.listen-address=127.0.0.1:9090");
    }

    private static Pattern readPaths() throws IOException {
        String header = locations().entrySet().stream()
                .filter(location -> location.getValue().contains("htpasswd.read"))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no location uses htpasswd.read"));
        assertThat(header).as("the read location is no longer a regex this test can evaluate").startsWith("~ ");
        return Pattern.compile(header.substring(2).trim());
    }

    private static Map<String, String> locations() throws IOException {
        Map<String, String> locations = new LinkedHashMap<>();
        Matcher matcher = LOCATION.matcher(read(PROXY));
        while (matcher.find()) {
            locations.put(matcher.group(1).trim(), matcher.group(2));
        }
        assertThat(locations).as("no location blocks parsed from %s", PROXY).hasSizeGreaterThanOrEqualTo(4);
        return locations;
    }

    private static String read(Path path) throws IOException {
        assertThat(path).exists();
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
