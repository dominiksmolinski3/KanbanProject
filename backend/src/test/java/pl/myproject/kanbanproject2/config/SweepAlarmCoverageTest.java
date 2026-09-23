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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SweepAlarmCoverageTest {
    private static final Path WORKFLOWS = Path.of("..", ".github", "workflows");

    private static final Path ALARM_WORKFLOW = WORKFLOWS.resolve("sweep-alarm.yml");

    private static Stream<String[]> sweeps() {
        return Stream.of(
                new String[] { "kanban-ci.yml", "trunk-alarm" },
                new String[] { "kanban-cd.yml", "cd-alarm" },
                new String[] { "dast.yml", "scan-alarm" },
                new String[] { "dependency-scan.yml", "scan-alarm" },
                new String[] { "external-scan.yml", "scan-alarm" },
                new String[] { "deployed-contract.yml", "contract-alarm" });
    }

    private static final Set<String> NOT_A_SWEEP = Set.of("push", "pull_request");

    @ParameterizedTest(name = "{0}")
    @DisplayName("the alarm waits on every other job in its workflow")
    @MethodSource("sweeps")
    void theAlarmCoversEveryJob(String workflow, String alarmJob) {
        Map<String, Object> jobs = jobs(workflow);

        assertThat(jobs)
                .as("the job that reports a red sweep to a person has gone from %s", workflow)
                .containsKey(alarmJob);

        Set<String> everythingElse = new LinkedHashSet<>(jobs.keySet());
        everythingElse.remove(alarmJob);

        assertThat(needsOf(workflow, alarmJob))
                .as("a job the alarm does not wait on can fail while the alarm reports success")
                .containsExactlyInAnyOrderElementsOf(everythingElse);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("the results it forwards name every job it waits on")
    @MethodSource("sweeps")
    void theResultsStringCoversEveryDependency(String workflow, String alarmJob) {
        String results = String.valueOf(with(workflow, alarmJob).get("results"));

        assertThat(needsOf(workflow, alarmJob))
                .allSatisfy(dependency -> assertThat(results)
                        .as("%s waits on '%s' and does not forward its result, so that job can "
                                + "fail with the alarm none the wiser", alarmJob, dependency)
                        .contains("needs." + dependency + ".result"));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("the workflow is still swept, which is what reaches a GITHUB_TOKEN merge")
    @MethodSource("sweeps")
    void theSweepIsStillScheduled(String workflow, String alarmJob) {
        Map<String, Object> on = triggers(workflow);

        assertThat(on)
                .as("without the sweep, a merge made with GITHUB_TOKEN runs nothing and nobody notices")
                .containsKey("schedule");
        assertThat(on)
                .as("the sweep cannot be run by hand to confirm a fix, so a red result waits a day")
                .containsKey("workflow_dispatch");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("the alarm runs whatever its dependencies did")
    @MethodSource("sweeps")
    void theAlarmRunsOnFailure(String workflow, String alarmJob) {
        assertThat(job(workflow, alarmJob).get("if"))
                .as("a job with no `if` is skipped the moment a dependency fails, which is exactly "
                        + "the case it exists for")
                .asString()
                .contains("always()");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("the alarm answers to every trigger that is a sweep, not just the cron")
    @MethodSource("sweeps")
    void theAlarmAnswersToEverySweepTrigger(String workflow, String alarmJob) {
        String condition = String.valueOf(job(workflow, alarmJob).get("if"));

        Set<String> sweeps = new LinkedHashSet<>(triggers(workflow).keySet());
        sweeps.removeIf(NOT_A_SWEEP::contains);

        assertThat(sweeps)
                .as("%s declares no trigger its alarm could fire on", workflow)
                .isNotEmpty();

        assertThat(sweeps)
                .allSatisfy(trigger -> assertThat(condition)
                        .as("%s runs on '%s' and the alarm does not fire on it, so that sweep runs "
                                + "and tells nobody the result", workflow, trigger)
                        .contains(trigger));
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("the alarm may write issues, which is the whole of how it reaches anybody")
    @MethodSource("sweeps")
    void theAlarmCanOpenAnIssue(String workflow, String alarmJob) {
        Object permissions = job(workflow, alarmJob).get("permissions");

        assertThat(permissions)
                .as("a called workflow gets the permissions of the job that calls it, so the grant "
                        + "has to be here or `gh issue create` fails and the alarm is a red step "
                        + "nobody reads")
                .isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> grants = (Map<String, Object>) permissions;

        assertThat(grants).containsEntry("issues", "write");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("the alarm delegates to the one implementation, rather than carrying a copy")
    @MethodSource("sweeps")
    void theAlarmUsesTheSharedWorkflow(String workflow, String alarmJob) {
        assertThat(ALARM_WORKFLOW)
                .as("the shared alarm this guard assumes every sweep calls has moved or gone")
                .exists();

        assertThat(job(workflow, alarmJob).get("uses"))
                .as("a sweep carrying its own copy of the alarm script is a copy that stops "
                        + "getting the next fix")
                .isEqualTo("./.github/workflows/sweep-alarm.yml");
    }

    @Test
    @DisplayName("the shared alarm assigns the issue it opens, because an unassigned one notifies nobody")
    void theSharedAlarmAssignsSomebody() {
        assertThat(read(ALARM_WORKFLOW))
                .as("an issue with no assignee is an issue nobody is subscribed to, which is the "
                        + "failure this whole change is about rather than a detail of it")
                .contains("--add-assignee");
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("a sweep cannot switch its own steps off and still report success")
    @MethodSource("sweeps")
    void theSweepCannotSkipItselfIntoSuccess(String workflow, String alarmJob) {
        String source = read(WORKFLOWS.resolve(workflow));

        assertThat(source)
                .as("%s writes a skip flag for its own steps to read; an unconfigured sweep must "
                        + "fail rather than pass having checked nothing", workflow)
                .doesNotContain("skip=true");

        assertThat(source)
                .as("a step in %s is gated on a skip flag, so the sweep can run green having done "
                        + "none of its work", workflow)
                .doesNotContain("outputs.skip");
    }

    private static Map<String, Object> with(String workflow, String alarmJob) {
        Object declared = job(workflow, alarmJob).get("with");

        assertThat(declared)
                .as("%s in %s forwards no inputs, so the alarm cannot know what failed",
                        alarmJob, workflow)
                .isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) declared;
        return inputs;
    }

    private static Map<String, Object> triggers(String workflow) {
        Map<String, Object> parsed = workflow(workflow);
        Object declared = parsed.containsKey(Boolean.TRUE) ? parsed.get(Boolean.TRUE) : parsed.get("on");

        assertThat(declared)
                .as("%s has no trigger block at all", workflow)
                .isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> on = (Map<String, Object>) declared;
        return on;
    }

    private static List<String> needsOf(String workflow, String alarmJob) {
        Object needs = job(workflow, alarmJob).get("needs");

        assertThat(needs)
                .as("%s in %s declares no dependencies, so it reports on nothing", alarmJob, workflow)
                .isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<String> declared = (List<String>) needs;
        return declared;
    }

    private static Map<String, Object> job(String workflow, String name) {
        @SuppressWarnings("unchecked")
        Map<String, Object> job = (Map<String, Object>) jobs(workflow).get(name);
        assertThat(job).as("job '%s' is gone from %s", name, workflow).isNotNull();
        return job;
    }

    private static Map<String, Object> jobs(String workflow) {
        Object jobs = workflow(workflow).get("jobs");
        assertThat(jobs).as("%s declares no jobs", workflow).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> declared = (Map<String, Object>) jobs;
        return declared;
    }

    private static Map<String, Object> workflow(String name) {
        Path path = WORKFLOWS.resolve(name);

        assertThat(path)
                .as("the workflow this guard reads has moved or gone")
                .exists();

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
