package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitRerunWorkflowTest {
    private static final Path WORKFLOWS = Path.of("..", ".github", "workflows");

    private static final String RERUN = "Rerun on rate limit";

    private static final Set<String> NOT_RERUN = Set.of(
            RERUN,
            "Sweep alarm",
            "Pull request changes",
            "PR conventions",
            "Dependabot auto-merge",
            "Monitoring VM converge");

    @Test
    @DisplayName("every workflow is either rerun on a rate limit or named here as one that is not")
    void everyWorkflowIsDecidedOn() throws IOException {
        Set<String> all = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                all.add((String) load(file).get("name"));
            }
        }

        Set<String> decided = new LinkedHashSet<>(watched());
        decided.addAll(NOT_RERUN);

        assertThat(all)
                .as("a workflow that is neither listed in rerun-on-rate-limit.yml nor excluded here "
                        + "is one nobody decided about, so its rate-limited runs stay red for a person")
                .isSubsetOf(decided);
        assertThat(watched())
                .as("a workflow named in rerun-on-rate-limit.yml no longer exists under that name, "
                        + "so the rerun never fires for it")
                .isSubsetOf(all);
    }

    @Test
    @DisplayName("it does not watch itself, and the workflows that act on a rerun are not watched")
    void itWatchesNothingItShouldNot() {
        assertThat(watched()).doesNotContainAnyElementsOf(NOT_RERUN);
    }

    @Test
    @DisplayName("it reruns a failed first attempt only, so a second 429 stays red")
    void itRerunsOnlyOnce() {
        String condition = String.valueOf(job().get("if"));

        assertThat(condition).contains("conclusion == 'failure'");
        assertThat(condition)
                .as("without the attempt guard a rerun that fails again is rerun again, for ever")
                .contains("run_attempt == 1");
    }

    @Test
    @DisplayName("it checks out the default branch only, never the code of the run it is looking at")
    void itDoesNotCheckOutTheRun() {
        String source = read(WORKFLOWS.resolve("rerun-on-rate-limit.yml"));

        assertThat(source)
                .as("a workflow_run job has a write token; checking out the failed run's head would "
                        + "execute a pull request's code with it")
                .doesNotContain("head_sha")
                .doesNotContain("head_branch")
                .doesNotContain("pull_request")
                .doesNotContain("ref:");
    }

    @Test
    @DisplayName("only the job gets write access, and only to actions")
    void onlyTheJobCanRerun() {
        assertThat(workflow().get("permissions")).isEqualTo(Map.of("contents", "read"));
        assertThat(job().get("permissions")).isEqualTo(Map.of("actions", "write", "contents", "read"));
    }

    @Test
    @DisplayName("run data reaches the script through env, never spliced into the shell")
    void dataGoesThroughEnv() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) job().get("steps");

        assertThat(steps)
                .filteredOn(step -> step.containsKey("run"))
                .allSatisfy(step -> assertThat(String.valueOf(step.get("run"))).doesNotContain("${{"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> watched() {
        Map<String, Object> on = (Map<String, Object>) triggersOf(workflow());
        Map<String, Object> run = (Map<String, Object>) on.get("workflow_run");

        assertThat(run).as("rerun-on-rate-limit.yml no longer runs on workflow_run").isNotNull();
        assertThat(run.get("types")).isEqualTo(List.of("completed"));
        return (List<String>) run.get("workflows");
    }

    private static Object triggersOf(Map<String, Object> parsed) {
        return parsed.containsKey(Boolean.TRUE) ? parsed.get(Boolean.TRUE) : parsed.get("on");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> job() {
        return (Map<String, Object>) ((Map<String, Object>) workflow().get("jobs")).get("rerun");
    }

    private static Map<String, Object> workflow() {
        return load(WORKFLOWS.resolve("rerun-on-rate-limit.yml"));
    }

    private static Map<String, Object> load(Path path) {
        assertThat(path).as("the workflow this guard reads has moved or gone").exists();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new Yaml().loadAs(reader, Map.class);
            return parsed;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        assertThat(path).as("the file this guard reads has moved or gone").exists();
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
