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
 *
 * <p><b>Why this exists, and why nothing else could have caught it.</b> The edge template is one
 * file used two ways: {@code docker-compose} points it at a bare container
 * ({@code API_UPSTREAM=http://app:8080}) and the deployment points it at an <em>ingress</em>
 * ({@code https://kanban-api-<env>.internal.<env-domain>}). A bare container answers whatever
 * arrives on its port. An ingress is a router, and it turns two requests away that the container
 * would have accepted: one without SNI, and one whose {@code Host} names a different app. Neither
 * rejection can happen locally, which is why the split broke twice in a row on exactly these two
 * lines and passed every suite in this repository both times.
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
 * <p><b>Then the same shape again, one layer up.</b> With SNI sent, the handshake succeeded and
 * every proxied call answered <b>404</b> — not Spring's 404, but the ingress's own
 * "Azure Container App - Unavailable" page. nginx was forwarding the browser's {@code Host}
 * ({@code kanban-web-<env>…}) to the API app's ingress, which routes by {@code Host} and has no
 * such app. Verified against the real origin: a valid SNI with a deliberately wrong {@code Host}
 * returns that same page, byte for byte. So {@code Host} has to be {@code $proxy_host} — the name
 * the upstream answers to — and the browser's own host moves to {@code X-Forwarded-Host}, which is
 * where anything that wants it should have been reading it anyway.
 *
 * <p>This is deliberately a set of text assertions over the template rather than behavioural ones.
 * A behavioural test needs an https upstream with a real certificate and an ingress that routes by
 * name, which is a network dependency in a suite that has none. Both were verified by hand instead,
 * each with a control: the same image against the same https upstream answers 403 with the TLS
 * directives and 502 without them; and against an upstream that echoes what it received, the fixed
 * image sends {@code Host: <upstream>} where the previous one sent {@code Host: <browser>}.
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
