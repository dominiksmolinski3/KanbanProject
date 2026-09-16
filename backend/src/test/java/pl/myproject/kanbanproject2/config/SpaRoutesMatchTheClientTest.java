package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build when {@link SpaRoutes} and {@code App.jsx} stop naming the same client routes.
 * The list used to have a natural enforcer - Spring permitted exactly these paths, so a route
 * forgotten here answered 403 on a refresh - but nginx now answers every path with the shell
 * regardless, leaving the array's only consumer (the deployed-contract sweep) with no way to tell
 * it has quietly fallen behind. Reads the client rather than the other way round, since the client
 * is where a route is really declared and this array is the copy.
 */
class SpaRoutesMatchTheClientTest {

    private static final Path APP_JSX = Path.of("..", "frontend", "src", "App.jsx");

    /** {@code <Route path="/board"} — the only form App.jsx uses, and a mismatch must not be silent. */
    private static final Pattern ROUTE_PATH =
            Pattern.compile("<Route\\b[^>]*?\\bpath\\s*=\\s*\"([^\"]*)\"", Pattern.DOTALL);

    @Test
    @DisplayName("SpaRoutes.ALL names every client route except /")
    void matchesTheClient() throws IOException {
        Set<String> declared = clientRoutes();

        assertThat(declared)
                .as("no <Route path=...> was found in App.jsx, so this test would pass on nothing "
                        + "rather than because the two lists agree")
                .isNotEmpty();

        // `/` is the shell's own address rather than a client route the server has to know about,
        // and SpaRoutes has always said so in as many words.
        declared.remove("/");

        assertThat(SpaRoutes.ALL)
                .as("SpaRoutes.ALL and App.jsx disagree. Its only consumers now are the "
                        + "deployed-contract sweep and this test, so a route missing here is a "
                        + "route the sweep silently stops checking rather than a deep link that "
                        + "403s.")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    private static Set<String> clientRoutes() throws IOException {
        assertThat(APP_JSX).as("App.jsx has moved or gone").isRegularFile();

        Set<String> routes = new LinkedHashSet<>();
        Matcher matcher = ROUTE_PATH.matcher(Files.readString(APP_JSX, StandardCharsets.UTF_8));
        while (matcher.find()) {
            routes.add(matcher.group(1));
        }
        return routes;
    }
}
