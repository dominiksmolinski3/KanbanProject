package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AzureApiVersionsAreStableTest {
    private static final Path REPO = Path.of("..");

    private static final List<String> ROOTS =
            List.of("terraform", "ansible", ".github", "observability", "loadtest", "backend/src/main");

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".terraform", "node_modules", "target");

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "tf", "tfvars", "hcl", "sh", "j2", "yml", "yaml", "json", "py", "js", "mjs", "java", "properties");

    private static final Pattern PIN = Pattern.compile(
            "api-version=([^&\"'\\s)]+)|\"Microsoft\\.[A-Za-z]+(?:/[A-Za-z]+)+@([^\"]+)\"");

    private static final Pattern PREVIEW = Pattern.compile("preview", Pattern.CASE_INSENSITIVE);

    private static final List<Pin> PINS = pins();

    record Pin(String where, String version) {
    }

    @Test
    @DisplayName("the guard still finds the API versions the repository pins")
    void theGuardFindsSomething() {
        assertThat(PINS)
                .extracting(Pin::version)
                .as("the azapi authConfigs pin and the Key Vault data-plane calls are gone, so this guard "
                        + "is reading the wrong tree rather than passing")
                .contains("2024-03-01", "7.4");
    }

    @Test
    @DisplayName("no Azure API version the repository pins is a preview")
    void noPinIsAPreview() {
        assertThat(PINS)
                .filteredOn(pin -> PREVIEW.matcher(pin.version()).find())
                .as("Azure retires a preview API version on 90 days' notice whenever a newer one ships; "
                        + "pin the latest stable version instead")
                .isEmpty();
    }

    private static List<Pin> pins() {
        List<Pin> pins = new ArrayList<>();
        for (String root : ROOTS) {
            Path dir = REPO.resolve(root);
            assertThat(dir).as("%s has moved or gone", dir).exists();
            try (Stream<Path> tree = Files.walk(dir)) {
                List<Path> files = tree.filter(Files::isRegularFile)
                        .filter(AzureApiVersionsAreStableTest::scanned)
                        .toList();
                for (Path file : files) {
                    collect(file, pins);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return pins;
    }

    private static boolean scanned(Path file) {
        for (Path part : REPO.relativize(file)) {
            if (SKIPPED_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        String name = file.getFileName().toString();
        return TEXT_EXTENSIONS.contains(name.substring(name.lastIndexOf('.') + 1));
    }

    private static void collect(Path file, List<Pin> into) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            Matcher pin = PIN.matcher(lines.get(i));
            while (pin.find()) {
                String version = pin.group(1) != null ? pin.group(1) : pin.group(2);
                into.add(new Pin(REPO.relativize(file) + ":" + (i + 1), version));
            }
        }
    }
}
