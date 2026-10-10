package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DockerHubMirrorCoverageTest {
    private static final Path WORKFLOWS = Path.of("..", ".github", "workflows");

    private static final String MIRROR = "./.github/actions/docker-hub-mirror";

    private static final Path MIRROR_ACTION = Path.of("..", ".github", "actions", "docker-hub-mirror", "action.yml");

    private static Stream<String[]> dockerHubJobs() {
        List<String[]> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml")).sorted().toList()) {
                jobs(file).forEach((name, job) -> {
                    if (steps(job).stream().anyMatch(DockerHubMirrorCoverageTest::logsInToDockerHub)) {
                        found.add(new String[] { file.getFileName() + " " + name, file.getFileName().toString(), name });
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found.stream();
    }

    @Test
    @DisplayName("the guard still finds the jobs that pull from Docker Hub")
    void theGuardFindsSomething() {
        assertThat(dockerHubJobs().count())
                .as("no workflow job logs in to Docker Hub any more, so this guard checks nothing")
                .isGreaterThanOrEqualTo(8);
        assertThat(MIRROR_ACTION).as("the shared mirror action has moved or gone").exists();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("a job that pulls from Docker Hub goes through the mirror, after a checkout and before Buildx")
    @MethodSource("dockerHubJobs")
    void theJobUsesTheMirror(String label, String workflow, String jobName) {
        List<Map<String, Object>> steps = steps(jobs(WORKFLOWS.resolve(workflow)).get(jobName));
        List<String> uses = steps.stream().map(step -> String.valueOf(step.get("uses"))).toList();

        int mirror = uses.indexOf(MIRROR);
        assertThat(mirror)
                .as("%s logs in to Docker Hub without the mirror, so its pulls spend the account's "
                        + "200-per-6-hours quota that every other job shares", label)
                .isNotNegative();

        int checkout = indexOfPrefix(uses, "actions/checkout@");
        assertThat(checkout)
                .as("%s runs a local action before checking the repository out, which fails the job", label)
                .isBetween(0, mirror);

        int buildx = indexOfPrefix(uses, "docker/setup-buildx-action@");
        if (buildx >= 0) {
            assertThat(buildx)
                    .as("%s creates its builder before the mirror exists", label)
                    .isGreaterThan(mirror);
            assertThat(steps.get(mirror).get("id")).isEqualTo("mirror");
            @SuppressWarnings("unchecked")
            Map<String, Object> with = (Map<String, Object>) steps.get(buildx).getOrDefault("with", Map.of());
            assertThat(String.valueOf(with.get("buildkitd-config-inline")))
                    .as("%s builds in a BuildKit container, which does not read the daemon's mirror and "
                            + "pulls every FROM straight from Docker Hub without its own config", label)
                    .isEqualTo("${{ steps.mirror.outputs.buildkitd-config }}");
        }
    }

    private static boolean logsInToDockerHub(Map<String, Object> step) {
        if (!String.valueOf(step.get("uses")).startsWith("docker/login-action@")) {
            return false;
        }
        Object with = step.get("with");
        Object registry = with instanceof Map<?, ?> inputs ? inputs.get("registry") : null;
        return registry == null || String.valueOf(registry).contains("docker.io");
    }

    private static int indexOfPrefix(List<String> uses, String prefix) {
        for (int i = 0; i < uses.size(); i++) {
            if (uses.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(Object job) {
        Object steps = ((Map<String, Object>) job).get("steps");
        return steps instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jobs(Path workflow) {
        try (Reader reader = Files.newBufferedReader(workflow, StandardCharsets.UTF_8)) {
            Map<String, Object> parsed = new Yaml().loadAs(reader, Map.class);
            Object jobs = parsed.get("jobs");
            return jobs instanceof Map<?, ?> ? (Map<String, Object>) jobs : Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
