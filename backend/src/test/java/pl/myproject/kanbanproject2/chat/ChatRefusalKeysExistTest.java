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

/**
 * A rule in two files, checked in one - {@code DeadLetterAlertTest}'s shape, applied to the one
 * thing the server now says to a person in words it does not own.
 *
 * <p>{@link ChatRefusal} carries a translation key rather than a sentence, which is the activity
 * feed's rule and is what lets the same refusal read in nine languages. The cost of that is a
 * coupling no compiler sees: the key is written in Java and resolved in
 * {@code frontend/public/locales}, and a rename on either side is a toast that renders the key
 * itself. Nothing else can see it - the client never names these keys in its own source, because
 * the server is what chooses them, so the frontend's own {@code i18n.test.js} cannot either.
 *
 * <p>It asserts all nine bundles, not just English: a key present in {@code en} and missing from
 * {@code ja} is a Japanese screen showing {@code chat.errors.tooLong}.
 */
class ChatRefusalKeysExistTest {

    /** Tests run with {@code backend/} as the working directory, so the repository root is up one. */
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

    /**
     * Walks {@code chat.errors.tooLong} through the bundle's nesting by text rather than by parsing
     * it, which keeps this test free of a JSON dependency the backend has no other use for: each
     * segment but the last must appear as an object key, and the last as a key with a string value.
     */
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
