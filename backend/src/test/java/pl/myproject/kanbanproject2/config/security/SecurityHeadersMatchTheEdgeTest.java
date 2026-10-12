package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersMatchTheEdgeTest {
    private static final Path TEMPLATE = Path.of("..", "frontend", "nginx", "default.conf.template");
    private static final String INCLUDE = "include /etc/nginx/snippets/security-headers.conf;";

    private static final Pattern LOCATION =
            Pattern.compile("^ {4}location\\s+([^{]+)\\{", Pattern.MULTILINE);

    @Test
    @DisplayName("the page policy is the edge's, and the API keeps its own locked-down one")
    void thePagePolicyIsNotTheApis() {
        assertThat(EdgeSecurityHeaders.contentSecurityPolicy())
                .as("the edge serves index.html, so its policy is the one a browser applies to the "
                        + "document; the API answers JSON and sends a policy that allows nothing")
                .isNotEqualTo(SecurityHeaders.CONTENT_SECURITY_POLICY)
                .contains("default-src 'self'")
                .contains("frame-ancestors 'none'");
    }

    @Test
    @DisplayName("Permissions-Policy switches off every capability rather than listing an allowance")
    void everyCapabilityIsRefused() {
        assertThat(EdgeSecurityHeaders.read().get("Permissions-Policy"))
                .contains("camera=()")
                .contains("microphone=()")
                .contains("geolocation=()")
                .contains("payment=()")
                .doesNotContain("self")
                .doesNotContain("*");
    }

    @Test
    @DisplayName("the edge sends HSTS with the declared max-age, includeSubDomains, and no preload")
    void sendsHsts() {
        String hsts = EdgeSecurityHeaders.read().get("Strict-Transport-Security");

        assertThat(hsts)
                .as("HSTS is the header this deployment never sent once, because Spring's default "
                        + "writer is gated on request.isSecure() behind an ingress that terminates "
                        + "TLS. The edge has the same problem and the same answer: write it always.")
                .isNotNull();
        assertThat(hsts).contains("max-age=" + SecurityHeaders.STRICT_TRANSPORT_SECURITY_MAX_AGE);
        assertThat(hsts).contains("includeSubDomains");
        assertThat(hsts)
                .as("preload is a one-way door on azurecontainerapps.io, which this deployment "
                        + "does not own")
                .doesNotContain("preload");
    }

    @Test
    @DisplayName("the edge sends the fixed headers Spring Security sends")
    void sendsTheFixedHeaders() {
        Map<String, String> headers = EdgeSecurityHeaders.read();

        assertThat(headers).containsEntry("X-Content-Type-Options", "nosniff");
        assertThat(headers).containsEntry("X-Frame-Options", "DENY");
        assertThat(headers).containsEntry("Referrer-Policy", "strict-origin-when-cross-origin");
        assertThat(headers).containsEntry("Cross-Origin-Opener-Policy", "same-origin");
        assertThat(headers).containsEntry("Cross-Origin-Resource-Policy", "same-origin");
    }

    @Test
    @DisplayName("Cross-Origin-Embedder-Policy is declined at the edge too")
    void declinesEmbedderPolicy() {
        assertThat(EdgeSecurityHeaders.read()).doesNotContainKey("Cross-Origin-Embedder-Policy");
    }

    @Test
    @DisplayName("every header the edge sets is set with `always`")
    void setsEveryHeaderAlways() {
        Matcher matcher = EdgeSecurityHeaders.ADD_HEADER.matcher(EdgeSecurityHeaders.source());
        while (matcher.find()) {
            assertThat(matcher.group(3))
                    .as("add_header %s is missing `always`, so a 404 or a 502 from the edge is "
                            + "served without it", matcher.group(1))
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("every location that adds a header of its own includes the security headers")
    void everyLocationWithAHeaderIncludesTheSnippet() throws IOException {
        String template = read(TEMPLATE);

        Matcher locations = LOCATION.matcher(template);
        int blockStart = -1;
        String blockName = null;
        int found = 0;

        while (locations.find()) {
            if (blockName != null) {
                assertBlock(blockName, template.substring(blockStart, locations.start()));
            }
            blockName = locations.group(1).trim();
            blockStart = locations.end();
            found++;
        }

        assertThat(found)
                .as("no location blocks were found in the edge template, so this test is reading "
                        + "the wrong file rather than passing")
                .isNotZero();
        assertBlock(blockName, template.substring(blockStart));
    }

    private static void assertBlock(String name, String body) {
        if (!body.contains("add_header")) {
            return;
        }
        assertThat(body)
                .as("`location %s` adds a header of its own, and nginx's add_header does not "
                        + "inherit into a block that does — so without the include it serves that "
                        + "path with no security headers at all", name)
                .contains(INCLUDE);
    }

    private static String read(Path path) throws IOException {
        assertThat(path).as("%s has moved or gone", path).isRegularFile();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
