package pl.myproject.kanbanproject2.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRefusalKeysExistTest {
    private static final Path CLIENT_LOCALES = Path.of("..", "frontend", "public", "locales");

    @Test
    @DisplayName("every refusal key the server can send is in every client bundle")
    void everyKeyIsTranslatedEverywhere() throws IOException {
        List<String> keys = refusalKeys();
        assertThat(keys)
                .as("the constants on ChatRefusal - a silent zero would pass everything below")
                .isNotEmpty();

        assertThat(CLIENT_LOCALES)
                .as("the client's locale bundles have moved or gone")
                .isDirectory();

        List<String> missing = new ArrayList<>();
        try (Stream<Path> languages = Files.list(CLIENT_LOCALES)) {
            for (Path language : languages.filter(Files::isDirectory).toList()) {
                String bundle = Files.readString(
                        language.resolve("translation.json"), StandardCharsets.UTF_8);
                for (String key : keys) {
                    if (!resolves(bundle, key)) {
                        missing.add(language.getFileName() + " -> " + key);
                    }
                }
            }
        }

        assertThat(missing)
                .as("a refusal the server sends and a bundle cannot render shows the key on screen")
                .isEmpty();
    }

    private static List<String> refusalKeys() {
        return Arrays.stream(ChatRefusal.class.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> field.getType() == String.class)
                .map(field -> {
                    try {
                        return (String) field.get(null);
                    } catch (IllegalAccessException e) {
                        throw new AssertionError("could not read " + field.getName(), e);
                    }
                })
                .toList();
    }

    private static boolean resolves(String bundle, String key) {
        String[] segments = key.split("\\.");
        int at = 0;
        for (int i = 0; i < segments.length; i++) {
            int found = bundle.indexOf('"' + segments[i] + '"', at);
            if (found < 0) {
                return false;
            }
            at = found + segments[i].length() + 2;
        }
        return true;
    }
}
