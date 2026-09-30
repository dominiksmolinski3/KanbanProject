package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class PinnedImagesTest {
    private static final Path REPO = Path.of("..");
    private static final Path COMPOSE = REPO.resolve("docker-compose.yml");
    private static final Path BROKER = REPO.resolve(Path.of("terraform", "modules", "broker", "main.tf"));

    private static final Pattern COMPOSE_IMAGE = Pattern.compile("(?m)^\\s+image:\\s*(\\S+)\\s*$");
    private static final Pattern TERRAFORM_IMAGE = Pattern.compile("(?m)^\\s+image\\s*=\\s*\"([^\"]+)\"");
    private static final Path POSTGRES = REPO.resolve(Path.of("terraform", "modules", "postgres", "main.tf"));
    private static final Pattern SERVER_VERSION = Pattern.compile("(?m)^\\s+version\\s*=\\s*\"(\\d+)\"");
    private static final Path BACKUP = REPO.resolve(Path.of("terraform", "modules", "backup", "main.tf"));
    private static final Pattern PINNED =Pattern.compile(".+:[^@/]+@sha256:[0-9a-f]{64}");

    @Test
    @DisplayName("every image docker-compose pulls names a tag and the digest it resolved to")
    void composeImagesArePinned() throws IOException {
        List<String> images = imagesIn(COMPOSE, COMPOSE_IMAGE);

        assertThat(images).as("images found in docker-compose.yml").hasSizeGreaterThanOrEqualTo(5);
        assertThat(images)
                .as("a bare tag is whatever the registry serves that day, so the stack CI tests is not "
                        + "the one somebody runs a week later; keep the tag for readers and add the digest")
                .allMatch(image -> PINNED.matcher(image).matches());
    }

    @Test
    @DisplayName("the deployed broker runs the same pinned RabbitMQ image as the local stack")
    void theBrokerMatchesCompose() throws IOException {
        String composeBroker = imagesIn(COMPOSE, COMPOSE_IMAGE).stream()
                .filter(image -> image.startsWith("rabbitmq:"))
                .findFirst()
                .orElseThrow();

        assertThat(imagesIn(BROKER, TERRAFORM_IMAGE))
                .as("Dependabot bumps the digest in docker-compose.yml only; move modules/broker with it, "
                        + "or the deployment runs a broker no CI run has used")
                .containsExactly(composeBroker);
    }

    @Test
    @DisplayName("the local stack runs the Postgres major version the deployed server runs")
    void composePostgresMatchesTheServer() throws IOException {
        String composePostgres = imagesIn(COMPOSE, COMPOSE_IMAGE).stream()
                .filter(image -> image.startsWith("postgres:"))
                .findFirst()
                .orElseThrow();
        String composeMajor = composePostgres.substring("postgres:".length()).split("[.@-]")[0];

        assertThat(imagesIn(POSTGRES, SERVER_VERSION))
                .as("every migration and query is tested against the compose Postgres; a different major "
                        + "than the Flexible Server's is a database no deploy runs on")
                .containsExactly(composeMajor);
    }

    @Test
    @DisplayName("the backup job pins its images and dumps with the server's major version")
    void theBackupJobMatchesTheServer() throws IOException {
        List<String> images = imagesIn(BACKUP, TERRAFORM_IMAGE);
        String dumper = images.stream().filter(image -> image.startsWith("postgres:")).findFirst().orElseThrow();
        String dumperMajor = dumper.substring("postgres:".length()).split("[.@-]")[0];

        assertThat(images).allMatch(image -> PINNED.matcher(image).matches());
        assertThat(imagesIn(POSTGRES, SERVER_VERSION))
                .as("pg_dump refuses a server newer than itself, so a server upgrade without this is a "
                        + "nightly backup that fails from that night on")
                .containsExactly(dumperMajor);
    }

    private static List<String> imagesIn(Path file, Pattern pattern) throws IOException {
        return pattern.matcher(Files.readString(file)).results().map(match -> match.group(1)).toList();
    }
}
