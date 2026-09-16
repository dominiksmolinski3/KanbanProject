package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build if the edge stops speaking to the API the way a Container Apps ingress requires.
 * The edge template is one file used two ways: {@code docker-compose} points it at a bare
 * container, and the deployment points it at an <em>ingress</em>, which is a router and turns away
 * two requests a bare container would have accepted - one without SNI, one whose {@code Host}
 * names a different app. Neither rejection can happen locally, which is why the container split
 * broke twice in a row on exactly these two lines and passed every suite in this repository both
 * times: first a 502 on every API call (SNI defaults off, so envoy can't tell which app the TLS
 * connection is for), then, once SNI was fixed, a 404 that was really the ingress's own
 * "Unavailable" page because {@code Host} still carried the browser's name instead of
 * {@code $proxy_host}.
 *
 * <p>This is deliberately a set of text assertions over the template rather than behavioural ones -
 * a behavioural test needs a real https upstream and an ingress that routes by name, a network
 * dependency this suite has none of. Both directives were verified by hand instead: the same image
 * against the same https upstream answers 403 with the TLS directives and 502 without them.
 */
class EdgeUpstreamTest {

    private static final Path TEMPLATE = Path.of("..", "frontend", "nginx", "default.conf.template");

    @Test
    @DisplayName("the edge sends SNI to the API upstream")
    void sendsSni() throws IOException {
        assertThat(directives())
                .as("proxy_ssl_server_name defaults to off. Container Apps routes an internal "
                        + "ingress by SNI, so without this every proxied call is a 502 with a "
                        + "healthy API container behind it.")
                .contains("proxy_ssl_server_name on;");
    }

    @Test
    @DisplayName("the edge verifies the API's certificate against a trust anchor")
    void verifiesTheUpstreamCertificate() throws IOException {
        String template = directives();

        assertThat(template)
                .as("proxy_ssl_verify defaults to off, which leaves the hop encrypted and "
                        + "unauthenticated - the trade this deployment refused for Postgres when it "
                        + "chose sslmode=verify-full over require")
                .contains("proxy_ssl_verify on;");
        assertThat(template)
                .as("verification with no trust anchor configured is not verification")
                .contains("proxy_ssl_trusted_certificate ");
    }

    @Test
    @DisplayName("the edge sends the upstream the name the upstream answers to")
    void sendsTheUpstreamsOwnHost() throws IOException {
        String template = directives();

        assertThat(template)
                .as("a Container Apps ingress routes by Host. Forwarding the browser's Host to the "
                        + "API app's ingress gets its \"Unavailable\" page - a 404 that looks like "
                        + "a missing route in Spring and is not one.")
                .contains("proxy_set_header Host $proxy_host;");
        assertThat(template)
                .as("the browser's host still has to reach the application somehow, and this is "
                        + "the header that carries it once Host names the upstream")
                .contains("proxy_set_header X-Forwarded-Host $host;");
        assertThat(template)
                .as("a leftover `Host $host` on any proxied location is the same 404 on that path "
                        + "alone, which is worse than all of them because it looks like a routing "
                        + "bug in the application")
                .doesNotContain("proxy_set_header Host $host;");
    }

    @Test
    @DisplayName("the name being verified is the host the upstream resolved to")
    void verifiesTheNameItResolved() throws IOException {
        // $proxy_host is proxy_ssl_name's default; written out so a reader does not have to know
        // that to see which name is being asserted. Anything else here would verify a certificate
        // against a hostname nobody connected to.
        assertThat(directives()).contains("proxy_ssl_name $proxy_host;");
    }

    /**
     * The template with runs of whitespace collapsed, since the directives in it are column-aligned
     * and what is being asserted is that a directive is present, not how it is spaced.
     */
    private static String directives() throws IOException {
        assertThat(TEMPLATE).as("the edge template has moved or gone").isRegularFile();
        return Files.readString(TEMPLATE, StandardCharsets.UTF_8).replaceAll("[ \\t]+", " ");
    }
}
