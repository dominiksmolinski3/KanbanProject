package pl.myproject.kanbanproject2.config.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class StompDestinationsMatchTheClientTest {

    private static final Path CLIENT_SOURCE = Path.of("..", "frontend", "src");

    private static final List<String> EXTENSIONS = List.of(".js", ".jsx", ".ts", ".tsx");

    private static final Pattern SUBSCRIBE = Pattern.compile("\\.subscribe\\(\\s*(['\"`])(.*?)\\1", Pattern.DOTALL);

    private static final Pattern SEND = Pattern.compile("destination:\\s*(['\"`])(.*?)\\1", Pattern.DOTALL);

    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{[^}]*}");

    @Test
    @DisplayName("every destination the client subscribes to is one the server lets through")
    void everyClientSubscriptionIsAllowed() {
        Map<String, String> subscriptions = destinations(SUBSCRIBE);

        assertThat(subscriptions)
                .as("no .subscribe('...') call was found in frontend/src; this guard has gone stale")
                .containsKeys("/topic/boards.7", "/topic/boards.7.chat", "/user/queue/messages");
        subscriptions.forEach((destination, site) -> assertThat(StompDestinationPolicy.allowsSubscribe(destination))
                .as("%s subscribes to %s, which StompDestinationPolicy drops, so the screen would quietly "
                        + "never update. Allow the destination there, with the check it needs.", site, destination)
                .isTrue());
    }

    @Test
    @DisplayName("every destination the client sends to is a @MessageMapping, not the broker")
    void everyClientSendIsAllowed() {
        Map<String, String> sends = destinations(SEND);

        assertThat(sends)
                .as("no destination: '...' was found in frontend/src; this guard has gone stale")
                .containsKey("/app/chat.sendMessage");
        sends.forEach((destination, site) -> assertThat(StompDestinationPolicy.allowsSend(destination))
                .as("%s sends to %s, which StompDestinationPolicy drops", site, destination)
                .isTrue());
    }

    private static Map<String, String> destinations(Pattern pattern) {
        var found = new TreeMap<String, String>();
        for (Path file : clientFiles()) {
            String source = read(file);
            pattern.matcher(source).results().forEach(match -> found.putIfAbsent(
                    INTERPOLATION.matcher(match.group(2)).replaceAll("7"),
                    CLIENT_SOURCE.relativize(file).toString()));
        }
        return found;
    }

    private static List<Path> clientFiles() {
        assertThat(CLIENT_SOURCE).as("frontend/src has moved; this guard reads it by path").isDirectory();
        try (Stream<Path> files = Files.walk(CLIENT_SOURCE)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(file -> EXTENSIONS.stream().anyMatch(file.toString()::endsWith))
                    .filter(file -> !file.toString().contains("__tests__"))
                    .filter(file -> !file.getFileName().toString().contains(".test."))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
