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

    private static final Pattern REST_PIN = Pattern.compile("api-version=([^&\"'\\s)]+)");

    private static final Pattern AZAPI_PIN = Pattern.compile("\"(Microsoft\\.[A-Za-z]+)(?:/[A-Za-z]+)+@([^\"]+)\"");

    private static final Pattern KEY_VAULT_CONTROL_PLANE =
            Pattern.compile("/providers/Microsoft\\.KeyVault/", Pattern.CASE_INSENSITIVE);

    private static final Pattern PREVIEW = Pattern.compile("preview", Pattern.CASE_INSENSITIVE);

    private static final String KEY_VAULT = "Microsoft.KeyVault";

    private static final String KEY_VAULT_CONTROL_PLANE_FLOOR = "2026-02-01";

    private static final List<Pin> PINS = pins();

    record Pin(String where, String namespace, String version) {
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

    @Test
    @DisplayName("a Key Vault control-plane version is " + KEY_VAULT_CONTROL_PLANE_FLOOR + " or later")
    void keyVaultControlPlanePinsAreCurrent() {
        assertThat(PINS)
                .filteredOn(pin -> KEY_VAULT.equalsIgnoreCase(pin.namespace()))
                .filteredOn(pin -> pin.version().compareTo(KEY_VAULT_CONTROL_PLANE_FLOOR) < 0)
                .as("every Key Vault control-plane API version before %s retires on 2027-02-27",
                        KEY_VAULT_CONTROL_PLANE_FLOOR)
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
            String line = lines.get(i);
            String where = REPO.relativize(file) + ":" + (i + 1);

            Matcher rest = REST_PIN.matcher(line);
            while (rest.find()) {
                String namespace = KEY_VAULT_CONTROL_PLANE.matcher(line).find() ? KEY_VAULT : null;
                into.add(new Pin(where, namespace, rest.group(1)));
            }

            Matcher azapi = AZAPI_PIN.matcher(line);
            while (azapi.find()) {
                into.add(new Pin(where, azapi.group(1), azapi.group(2)));
            }
        }
    }
}
