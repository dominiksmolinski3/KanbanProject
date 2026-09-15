package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build if the edge stops sending SNI, or stops checking the certificate it gets back,
 * when it proxies to the API.
 *
 * <p><b>Why this exists, and why nothing else could have caught it.</b> The edge template is one
 * file used two ways: {@code docker-compose} sets {@code API_UPSTREAM=http://app:8080} and the
 * deployment sets {@code https://kanban-api-<env>.internal.<env-domain>}. The scheme is the single
 * difference between the stack every guard here runs against and the stack that actually serves
 * users — and it is the half nothing local exercises, because over {@code http} every directive
 * below is inert.
 *
 * <p>So it went wrong exactly there. {@code proxy_ssl_server_name} defaults to <b>off</b>, which
 * means nginx opens TLS to the ingress naming nobody; Container Apps routes by SNI, so envoy cannot
 * tell which app the connection belongs to and resets the handshake. The first apply of the
 * container split produced a perfectly healthy API container, a perfectly healthy edge, a deployed
 * contract sweep that passed every static claim, and a 502 on every single API call:
 *
 * <pre>
 *   peer closed connection in SSL handshake (104: Connection reset by peer)
 *   while SSL handshaking to upstream, upstream: "https://100.100.0.208:443/api/columns"
 * </pre>
 *
 * <p>{@code proxy_ssl_verify} is the other default, and it is the one that would have been worse
 * for being invisible: without it the hop is encrypted and unauthenticated, which is the same trade
 * this deployment refused when it chose {@code sslmode=verify-full} over {@code require} for
 * Postgres. The design note claimed the hop was authenticated. It was not, and nothing said so.
 *
 * <p>This is deliberately a text assertion over the template rather than a behavioural one. A
 * behavioural test needs an https upstream with a real certificate, which is a network dependency
 * in a suite that has none. The two were verified by hand instead, with a control: the same image
 * against the same https upstream answers 403 with these directives and 502 without them.
 */
class EdgeUpstreamTlsTest {

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
    @DisplayName("the name being verified is the host the upstream resolved to")
    void verifiesTheNameItResolved() throws IOException {
        // $proxy_host is proxy_ssl_name's default; written out so a reader does not have to know
        // that to see which name is being asserted. Anything else here would verify a certificate
        // against a hostname nobody connected to.
        assertThat(directives()).contains("proxy_ssl_name $proxy_host;");
    }

    /**
     * The template with runs of whitespace collapsed.
     *
     * <p>nginx directives in that file are column-aligned, and a guard that breaks when somebody
     * re-aligns them is a guard people learn to edit rather than obey. What is being asserted is
     * that the directive is present and set, not how it is spaced.
     */
    private static String directives() throws IOException {
        assertThat(TEMPLATE).as("the edge template has moved or gone").isRegularFile();
        return Files.readString(TEMPLATE, StandardCharsets.UTF_8).replaceAll("[ \\t]+", " ");
    }
}
