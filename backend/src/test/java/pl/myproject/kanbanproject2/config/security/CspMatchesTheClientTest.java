package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A guard over the way a Content-Security-Policy actually fails.
 *
 * <p>A policy is almost never wrong on the day it is written - it is written by reading the client.
 * It goes wrong later, when somebody adds a font, an analytics snippet or an embedded widget, and
 * the symptom is not a failing test or a red build. It is a browser silently refusing one request
 * on one screen, usually a screen nobody in CI visits. Jest cannot see it, because jsdom enforces
 * no CSP; the backend suite cannot see it, because the host being added is in a stylesheet in
 * another tree.
 *
 * <p>So this reads that tree. Every {@code https://host} the client source mentions has to be a
 * host {@link SecurityHeaders#CONTENT_SECURITY_POLICY} names. It is the same shape as
 * {@code SupportedLocalesMatchClientTest} reading {@code frontend/public/locales} and
 * {@code DeadLetterAlertTest} reading the Terraform: a rule that lives in two trees, checked in
 * one, needing no browser and no running container.
 *
 * <p><b>What it cannot see, stated rather than implied.</b> A host reached only at runtime by
 * somebody else's script leaves no literal to find - {@code www.gstatic.com} is in the policy
 * because reCAPTCHA's own {@code api.js} fetches its implementation from there, and no file in this
 * repository says so. That is a real limit: this catches the host somebody adds to the client, not
 * the host a third party adds to itself. The ZAP baseline sweep and a browser console are what
 * catch the other kind.
 */
class CspMatchesTheClientTest {

    /** Tests run with {@code backend/} as the working directory, so the client is up and over. */
    private static final Path CLIENT_SOURCE = Path.of("..", "frontend", "src");
    private static final Path CLIENT_SHELL = Path.of("..", "frontend", "index.html");

    private static final Pattern EXTERNAL_HOST = Pattern.compile("https://([a-zA-Z0-9.-]+)");

    @Test
    @DisplayName("every external host the client names is one the policy allows")
    void theClientFetchesNothingThePolicyRefuses() throws IOException {
        assertThat(CLIENT_SOURCE)
                .as("the client source tree has moved; this guard is now reading nothing")
                .exists();

        Set<String> refused = new TreeSet<>(hostsTheClientNames());
        refused.removeIf(host -> SecurityHeaders.CONTENT_SECURITY_POLICY.contains(host));

        assertThat(refused)
                .as("the client fetches from these and the Content-Security-Policy does not allow "
                        + "them - the browser will refuse the request, on a screen no test opens, "
                        + "with nothing failing anywhere")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan finds the hosts it is meant to be watching")
    void theScanFindsSomething() throws IOException {
        // A silent zero passes the assertion above for the wrong reason, which is the one failure
        // mode this kind of guard has and the one it cannot report on its own.
        assertThat(hostsTheClientNames())
                .as("no external host found in the client at all - the scan has stopped reading")
                .contains("fonts.googleapis.com", "www.google.com");
    }

    @Test
    @DisplayName("script-src does not allow inline script, which is the half of a CSP worth having")
    void inlineScriptStaysRefused() {
        // Everything else in the policy is a list of hosts and can be argued about. This one is the
        // control: a CSP with 'unsafe-inline' in script-src stops an injected <script> from
        // nothing at all, and it is the single easiest thing to add while "fixing" a broken screen.
        String scriptSrc = directive("script-src");

        assertThat(scriptSrc).doesNotContain("'unsafe-inline'");
        assertThat(scriptSrc).doesNotContain("'unsafe-eval'");
        assertThat(directive("object-src")).contains("'none'");
        assertThat(directive("frame-ancestors")).contains("'none'");
        assertThat(directive("base-uri")).contains("'self'");
    }

    private static String directive(String name) {
        return Stream.of(SecurityHeaders.CONTENT_SECURITY_POLICY.split(";"))
                .map(String::trim)
                .filter(part -> part.startsWith(name + " "))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the policy no longer has a " + name + " directive: "
                                + SecurityHeaders.CONTENT_SECURITY_POLICY));
    }

    private static Set<String> hostsTheClientNames() throws IOException {
        Set<String> hosts = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(CLIENT_SOURCE)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                collectHosts(file, hosts);
            }
        }
        if (Files.exists(CLIENT_SHELL)) {
            collectHosts(CLIENT_SHELL, hosts);
        }
        return hosts;
    }

    private static void collectHosts(Path file, Set<String> into) throws IOException {
        Matcher host = EXTERNAL_HOST.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (host.find()) {
            into.add(host.group(1));
        }
    }
}
