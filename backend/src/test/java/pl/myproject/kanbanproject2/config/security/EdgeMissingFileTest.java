package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.config.SpaRoutes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static String template() throws IOException {
        assertThat(TEMPLATE).as("the edge template has moved or gone").isRegularFile();
        return Files.readString(TEMPLATE, StandardCharsets.UTF_8);
    }
}
