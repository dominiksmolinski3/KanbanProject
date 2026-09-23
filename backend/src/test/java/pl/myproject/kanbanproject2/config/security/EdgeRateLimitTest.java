package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pl.myproject.kanbanproject2.config.security.ratelimit.ApiRateLimitProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The edge's request limits, read out of the nginx template. Nothing local can exercise the part
 * that matters most - the deployment's ingress is what appends the client's address to
 * X-Forwarded-For, and compose has no ingress - so, like {@link EdgeUpstreamTest}, this is a text
 * assertion over the directives rather than a behavioural test.
 *
 * <p>Four claims, each one a way the limit could silently stop limiting:
 * <ul>
 *   <li>every location that proxies to the API is limited, or a new one is an unlimited way in;</li>
 *   <li>the key is the <em>last</em> X-Forwarded-For entry - the first is the client's own text,
 *       and keying on it lets a flood choose a fresh bucket per request;</li>
 *   <li>the 429 carries the security headers, since nginx generates it and Spring never sees it;</li>
 *   <li>the edge's API rate is above the application's per-account rate, so a signed-in person
 *       meets the per-account limit - with its Retry-After and fleet-wide accounting - first.</li>
 * </ul>
 */
class EdgeRateLimitTest {

    private static final Path TEMPLATE = Path.of("..", "frontend", "nginx", "default.conf.template");

    private static final Pattern LOCATION = Pattern.compile("location\\s+([^{]+)\\{([^}]*)}");

    private static String template() throws IOException {
        return Files.readString(TEMPLATE, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("every location that proxies to the API is limited")
    void everyProxyingLocationIsLimited() throws IOException {
        var matcher = LOCATION.matcher(template());
        int proxying = 0;
        while (matcher.find()) {
            String body = matcher.group(2);
            if (body.contains("proxy_pass")) {
                proxying++;
                assertThat(body)
                        .as("location %s proxies to the API with no limit_req", matcher.group(1).trim())
                        .containsPattern("limit_req\\s+zone=");
            }
        }
        assertThat(proxying).as("the location reader has stopped matching").isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("the key is the address the ingress appended, not one the client wrote")
    void keyedOnTheLastHop() throws IOException {
        String edge = template();

        assertThat(edge).contains("map $http_x_forwarded_for $edge_client");
        // Anchored at the end of the header: the entry after the last comma.
        assertThat(edge).containsPattern("\"~\\(\\?:\\^\\|,\\)[^\"]*\\$\"");
        assertThat(edge).containsPattern("default\\s+\\$remote_addr;");
        assertThat(edge).doesNotContain("limit_req_zone $binary_remote_addr")
                .doesNotContain("limit_req_zone $http_x_forwarded_for");

        var zones = Pattern.compile("limit_req_zone\\s+(\\S+)").matcher(edge).results().toList();
        assertThat(zones).as("no limit_req_zone found").isNotEmpty();
        zones.forEach(zone -> assertThat(zone.group(1)).isEqualTo("$edge_client"));
    }

    @Test
    @DisplayName("a refusal is answered as a 429 with the security headers and the application's error shape")
    void refusalIsAProperResponse() throws IOException {
        String edge = template();

        assertThat(edge).contains("limit_req_status    429;").contains("error_page 429 = @rate_limited;");
        var refusal = Pattern.compile("location @rate_limited \\{([^}]*)}").matcher(edge);
        assertThat(refusal.find()).as("the @rate_limited location is gone").isTrue();
        assertThat(refusal.group(1))
                .contains("include /etc/nginx/snippets/security-headers.conf;")
                .contains("Retry-After")
                .contains("\"code\":\"TOO_MANY_REQUESTS\"");
    }

    @Test
    @DisplayName("the edge lets a signed-in person reach the per-account limit before its own")
    void edgeIsLooserThanTheAccountLimit() throws Exception {
        var zone = Pattern.compile("zone=edge_api:\\S+\\s+rate=(\\d+)r/s").matcher(template());
        assertThat(zone.find()).as("the edge_api zone is gone").isTrue();
        int edgeRate = Integer.parseInt(zone.group(1));

        // Read off the record's @DefaultValue, which is the rate the application runs with unless an
        // environment overrides it - and none does.
        int accountRate = Integer.parseInt(ApiRateLimitProperties.class
                .getDeclaredConstructor(boolean.class, int.class, int.class)
                .getParameters()[1].getAnnotation(DefaultValue.class).value()[0]);

        assertThat(edgeRate)
                .as("the edge refuses at %d/s, below the %d/s an account is allowed", edgeRate, accountRate)
                .isGreaterThan(accountRate);
    }
}
