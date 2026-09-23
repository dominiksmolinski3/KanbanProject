package pl.myproject.kanbanproject2.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SupportedLocalesMatchClientTest {
    private static final Path CLIENT_LOCALES = Path.of("..", "frontend", "public", "locales");

    @Test
    @DisplayName("the languages an account can be set to are the ones the client has bundles for")
    void theTwoListsAgree() throws IOException {
        assertThat(CLIENT_LOCALES)
                .as("the client's locale bundles have moved or gone")
                .isDirectory();

        Set<String> onTheClient = new TreeSet<>();
        try (Stream<Path> entries = Files.list(CLIENT_LOCALES)) {
            entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .forEach(onTheClient::add);
        }

        assertThat(onTheClient)
                .as("a language on screen that the mail cannot write in, or the reverse")
                .containsExactlyElementsOf(SupportedLocales.sorted());
    }

    @Test
    @DisplayName("every supported language has a mail bundle of its own, except English which is the base one")
    void everyLanguageHasABundle() {
        for (String tag : SupportedLocales.TAGS) {
            String bundle = SupportedLocales.DEFAULT.equals(tag)
                    ? "/mail/messages.properties"
                    : "/mail/messages_" + tag + ".properties";
            assertThat(getClass().getResource(bundle))
                    .as("%s is a supported language with no mail bundle", tag)
                    .isNotNull();
        }
    }
}
