package pl.myproject.kanbanproject2.config.security;

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

/**
 * Fails the build when the edge stops sending what {@link SecurityHeaders} declares.
 *
 * <p>Splitting the deployment moved the document response. nginx serves {@code index.html} from its
 * own container now, and a {@code Content-Security-Policy} Spring writes reaches nobody on it —
 * which is the response a CSP is actually for. Spring keeps writing the same headers on API and
 * attachment responses, so the policy is one rule living in two files: this class is the thing that
 * stops them disagreeing, the same trade {@code DeadLetterAlertTest} makes against the Terraform
 * and {@code CspMatchesTheClientTest} makes against the client.
 *
 * <p>Two of the assertions here are about nginx rather than about the policy, and both are traps
 * this repository would otherwise hit exactly once:
 *
 * <ul>
 *   <li><b>{@code add_header} does not inherit.</b> A location that adds a header of its own — both
 *       of the file-serving locations add a {@code Cache-Control} — silently discards every header
 *       set at the level above. The shell would then be served with no policy at all, and nothing
 *       about the response would say so. Hence the snippet, and hence the check that every location
 *       adding a header includes it.</li>
 *   <li><b>{@code always}.</b> Without it a header is written on 2xx and 3xx only, so a 404 or a
 *       502 from the edge arrives unprotected — which are exactly the responses an injected script
 *       would like to be framed in.</li>
 * </ul>
 */
class SecurityHeadersMatchTheEdgeTest {

    private static final Path SNIPPET = Path.of("..", "frontend", "nginx", "security-headers.conf");
    private static final Path TEMPLATE = Path.of("..", "frontend", "nginx", "default.conf.template");
    private static final String INCLUDE = "include /etc/nginx/snippets/security-headers.conf;";

    /** {@code add_header Name "value" always;}, which is the only form the snippet uses. */
    private static final Pattern ADD_HEADER =
            Pattern.compile("^\\s*add_header\\s+(\\S+)\\s+\"([^\"]*)\"(\\s+always)?\\s*;", Pattern.MULTILINE);

    /** The opening line of each {@code location} block, so a block can be asked what it contains. */
    private static final Pattern LOCATION =
            Pattern.compile("^ {4}location\\s+([^{]+)\\{", Pattern.MULTILINE);

    @Test
    @DisplayName("the edge sends the Content-Security-Policy this repository declares")
    void sendsTheDeclaredCsp() throws IOException {
        assertThat(snippetHeaders().get("Content-Security-Policy"))
                .as("the edge serves index.html, so this is the copy of the policy a browser "
                        + "actually applies to the document")
                .isEqualTo(SecurityHeaders.CONTENT_SECURITY_POLICY);
    }

    @Test
    @DisplayName("the edge sends the Permissions-Policy this repository declares")
    void sendsTheDeclaredPermissionsPolicy() throws IOException {
        assertThat(snippetHeaders().get("Permissions-Policy"))
                .isEqualTo(SecurityHeaders.PERMISSIONS_POLICY);
    }

    @Test
    @DisplayName("the edge sends HSTS with the declared max-age, includeSubDomains, and no preload")
    void sendsHsts() throws IOException {
        String hsts = snippetHeaders().get("Strict-Transport-Security");

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
    void sendsTheFixedHeaders() throws IOException {
        Map<String, String> headers = snippetHeaders();

        assertThat(headers).containsEntry("X-Content-Type-Options", "nosniff");
        assertThat(headers).containsEntry("X-Frame-Options", "DENY");
        assertThat(headers).containsEntry("Referrer-Policy", "strict-origin-when-cross-origin");
        assertThat(headers).containsEntry("Cross-Origin-Opener-Policy", "same-origin");
        assertThat(headers).containsEntry("Cross-Origin-Resource-Policy", "same-origin");
    }

    @Test
    @DisplayName("Cross-Origin-Embedder-Policy is declined at the edge too")
    void declinesEmbedderPolicy() throws IOException {
        // SecurityHeadersTest asserts the same absence on the Spring side. Both are deliberate:
        // require-corp breaks the reCAPTCHA frame, which carries no CORP header of its own.
        assertThat(snippetHeaders()).doesNotContainKey("Cross-Origin-Embedder-Policy");
    }

    @Test
    @DisplayName("every header the edge sets is set with `always`")
    void setsEveryHeaderAlways() throws IOException {
        Matcher matcher = ADD_HEADER.matcher(read(SNIPPET));
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

    private static Map<String, String> snippetHeaders() throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        Matcher matcher = ADD_HEADER.matcher(read(SNIPPET));
        while (matcher.find()) {
            headers.put(matcher.group(1), matcher.group(2));
        }
        assertThat(headers)
                .as("no add_header directives were found in %s, which means this test is reading "
                        + "the wrong file rather than that the edge sends nothing", SNIPPET)
                .isNotEmpty();
        return headers;
    }

    private static String read(Path path) throws IOException {
        assertThat(path).as("%s has moved or gone", path).isRegularFile();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
