package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.config.SpaRoutes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeMissingFileTest {
    private static final Path TEMPLATE = Path.of("..", "frontend", "nginx", "default.conf.template");

    private static final Pattern LOCATION = Pattern.compile("location\\s+([^{]+)\\{([^}]*)}");

    private static final Pattern FILE_LOCATION = Pattern.compile("location\\s+~\\s+(\\S+)\\s*\\{([^}]*)}");

    @Test
    @DisplayName("a path naming a file that is not there is a 404, not the app shell")
    void aMissingFileIsNotTheShell() throws IOException {
        Matcher file = FILE_LOCATION.matcher(template());

        assertThat(file.find())
                .as("the extension location is gone, so try_files in `location /` answers "
                        + "/robots.txt, /sitemap.xml or a missing locale bundle with index.html and a 200 - "
                        + "which a scanner, a crawler and i18next each read as the file existing")
                .isTrue();
        assertThat(file.group(2).replaceAll("\\s+", " "))
                .as("the extension location has to end in a 404, or it is the shell fallback again")
                .contains("try_files $uri =404;");
    }

    @Test
    @DisplayName("no regex location can take a path the API owns")
    void everyProxyingLocationOutranksARegex() throws IOException {
        Matcher locations = LOCATION.matcher(template());
        int proxying = 0;
        while (locations.find()) {
            if (locations.group(2).contains("proxy_pass")) {
                proxying++;
                assertThat(locations.group(1).trim())
                        .as("a regex location beats a plain prefix, so without ^~ the extension "
                                + "location 404s SockJS's /ws/iframe.html and any /api path that ends in a dotted "
                                + "segment, such as a label named v1.2")
                        .startsWith("^~ ");
            }
        }
        assertThat(proxying).as("the location reader has stopped matching").isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("a dot-prefixed path is a file, so a missing one is a 404")
    void aDotfileIsNotTheShell() throws IOException {
        Matcher file = FILE_LOCATION.matcher(template());
        assertThat(file.find()).isTrue();
        Pattern location = Pattern.compile(file.group(1));

        assertThat(location.matcher("/._darcs").find()).isTrue();
        assertThat(location.matcher("/.git/config").find()).isTrue();
        assertThat(location.matcher("/.well-known/security.txt").find()).isTrue();
        assertThat(location.matcher("/board/7").find()).isFalse();
    }

    @Test
    @DisplayName("no client route looks like a file name")
    void noClientRouteHasAnExtension() throws IOException {
        Matcher file = FILE_LOCATION.matcher(template());
        assertThat(file.find()).isTrue();
        Pattern extension = Pattern.compile(file.group(1));

        for (String route : SpaRoutes.ALL) {
            assertThat(extension.matcher(route).find())
                    .as("%s matches the edge's extension location, so a refresh on it is a 404", route)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a path under /v3 that is not the API document is a 404, not the app shell")
    void theRestOfV3IsNotTheShell() throws IOException {
        Pattern location = Pattern.compile(v3Location().group(1));

        for (String path : List.of("/v3", "/v3/", "/v3/8091742919807082442")) {
            assertThat(location.matcher(path).find())
                    .as("%s falls through to try_files in `location /`, which answers it with index.html "
                            + "and a 200, so a client or a scanner reads the API as serving a web page", path)
                    .isTrue();
        }
        assertThat(location.matcher("/v3x").find()).isFalse();
        assertThat(location.matcher("/board/7").find()).isFalse();
    }

    @Test
    @DisplayName("no client route sits under /v3")
    void noClientRouteIsUnderV3() throws IOException {
        Pattern location = Pattern.compile(v3Location().group(1));

        for (String route : SpaRoutes.ALL) {
            assertThat(location.matcher(route).find())
                    .as("%s matches the edge's /v3 location, so a refresh on it is a 404", route)
                    .isFalse();
        }
    }

    private static Matcher v3Location() throws IOException {
        Matcher regex = FILE_LOCATION.matcher(template());
        while (regex.find()) {
            if (regex.group(2).contains("return 404;")) {
                return regex;
            }
        }
        throw new AssertionError("no regex location answers the rest of /v3 with a 404");
    }

    private static String template() throws IOException {
        assertThat(TEMPLATE).as("the edge template has moved or gone").isRegularFile();
        return Files.readString(TEMPLATE, StandardCharsets.UTF_8);
    }
}
