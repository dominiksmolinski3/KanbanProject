package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class BaseImagesCoverageTest {
    private static final Path REPO = Path.of("..");
    private static final Path IMAGES = REPO.resolve("images");
    private static final Path WORKFLOWS = REPO.resolve(Path.of(".github", "workflows"));
    private static final Path DEPENDABOT = REPO.resolve(Path.of(".github", "dependabot.yml"));
    private static final Path TERRAFORM = REPO.resolve("terraform");

    private static final Pattern FROM = Pattern.compile("(?m)^FROM\\s+(\\S+)");
    private static final Pattern TERRAFORM_IMAGE = Pattern.compile("(?m)^\\s+image\\s*=\\s*\"([^\"$]+)\"");
    private static final Pattern OWN_IMAGE = Pattern.compile("ghcr\\.io/[^/]+/(kanbanproject-[a-z]+):.+");

    @Test
    @DisplayName("every wrapper under images/ is rebuilt, linted and bumped")
    void everyWrapperIsCovered() throws IOException {
        List<String> wrappers = wrappers();
        assertThat(wrappers).as("no wrapper Dockerfile under images/, so this guard checks nothing").hasSizeGreaterThanOrEqualTo(3);

        List<Map<String, Object>> matrix = rebuildMatrix();
        List<Object> hadolint = matrix(WORKFLOWS.resolve("hadolint.yml"), "hadolint", "dockerfile");
        List<Object> dependabot = dockerDirectories();

        for (String wrapper : wrappers) {
            String context = "images/" + wrapper;
            assertThat(matrix)
                    .as("%s is not in base-images.yml, so nothing ever builds it and its CVEs are never fixed", context)
                    .anySatisfy(entry -> assertThat(entry).containsEntry("context", context)
                            .containsEntry("image", "kanbanproject-" + wrapper));
            assertThat(hadolint).as("hadolint.yml does not lint %s/Dockerfile", context).contains(context + "/Dockerfile");
            assertThat(dependabot).as("Dependabot never bumps the base %s builds on", context).contains("/" + context);
        }
    }

    @Test
    @DisplayName("the rebuild looks the pin up under the repository its Dockerfile builds on")
    void theUpstreamMatchesTheDockerfile() throws IOException {
        for (Map<String, Object> entry : rebuildMatrix()) {
            Matcher from = FROM.matcher(Files.readString(REPO.resolve(entry.get("context") + "/Dockerfile")));
            assertThat(from.find()).isTrue();
            String repository = from.group(1).split("@")[0];
            repository = repository.substring(0, repository.lastIndexOf(':'));

            assertThat(entry.get("upstream"))
                    .as("base-images.yml finds the pin to replace by its upstream name; a different one finds "
                            + "nothing until the first rebuild and fails the run")
                    .isEqualTo(repository);
        }
    }

    @Test
    @DisplayName("every image Terraform pins goes through a wrapper the daily rebuild covers")
    void terraformPinsOnlyWrappedImages() throws IOException {
        List<Map<String, Object>> matrix = rebuildMatrix();
        List<String> pins;
        try (Stream<Path> files = Files.walk(TERRAFORM)) {
            pins = files.filter(file -> file.toString().endsWith(".tf"))
                    .filter(file -> !file.toString().contains(".terraform"))
                    .flatMap(file -> TERRAFORM_IMAGE.matcher(read(file)).results().map(match -> match.group(1)))
                    .toList();
        }
        assertThat(pins).as("no image is pinned under terraform/, so this guard checks nothing").isNotEmpty();

        assertThat(pins).allSatisfy(pin -> {
            Matcher own = OWN_IMAGE.matcher(pin);
            String repository = pin.split("@")[0];
            String upstream = repository.substring(0, repository.lastIndexOf(':'));
            assertThat(matrix)
                    .as("%s is pinned in Terraform with no wrapper under images/, so an OS-package CVE in it "
                            + "waits for upstream to rebuild", pin)
                    .anySatisfy(entry -> assertThat(own.matches()
                            ? entry.get("image").equals(own.group(1))
                            : entry.get("upstream").equals(upstream)).isTrue());
        });
    }

    private static List<String> wrappers() throws IOException {
        try (Stream<Path> dirs = Files.list(IMAGES)) {
            return dirs.filter(dir -> Files.isRegularFile(dir.resolve("Dockerfile")))
                    .map(dir -> dir.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rebuildMatrix() throws IOException {
        Map<String, Object> strategy = (Map<String, Object>) job(WORKFLOWS.resolve("base-images.yml"), "rebuild").get("strategy");
        Map<String, Object> matrix = (Map<String, Object>) strategy.get("matrix");
        return (List<Map<String, Object>>) matrix.get("include");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> matrix(Path workflow, String job, String key) throws IOException {
        Map<String, Object> strategy = (Map<String, Object>) job(workflow, job).get("strategy");
        return (List<Object>) ((Map<String, Object>) strategy.get("matrix")).get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> job(Path workflow, String name) throws IOException {
        Map<String, Object> parsed = new Yaml().load(Files.readString(workflow));
        Map<String, Object> job = (Map<String, Object>) ((Map<String, Object>) parsed.get("jobs")).get(name);
        assertThat(job).as("job '%s' is gone from %s", name, workflow).isNotNull();
        return job;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> dockerDirectories() throws IOException {
        Map<String, Object> parsed = new Yaml().load(Files.readString(DEPENDABOT));
        return ((List<Map<String, Object>>) parsed.get("updates")).stream()
                .filter(update -> "docker".equals(update.get("package-ecosystem")))
                .findFirst()
                .map(update -> (List<Object>) update.get("directories"))
                .orElseThrow();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
