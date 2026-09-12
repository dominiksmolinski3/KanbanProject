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

/**
 * A guard over the thing every unattended workflow here can lose silently: the job that tells
 * somebody it went red.
 *
 * <p>These workflows run on a cron. That trigger exists because a push made with the default
 * {@code GITHUB_TOKEN} does not start a workflow run - and Dependabot's auto-merge job merges
 * with exactly that token. That is how a merge commit reached {@code main} with {@code react} and
 * {@code react-dom} on different versions and nothing ran the suite against the result, and on
 * 12 Sep 2026 it was measured doing the same to {@code kanban-cd.yml}: five consecutive merge
 * commits, no image built for any of them, {@code :latest} quietly no longer the tip of
 * {@code main}. The sweeps close that hole; the alarm job is what turns a red sweep into
 * something a person is told about.
 *
 * <p>Three couplings hold each alarm together and none of them is visible to YAML, to Actions, or
 * to any linter. Each is checked here because there is nowhere else it can be checked.
 *
 * <p><b>The alarm is only as wide as its {@code needs} list.</b> A job added to a workflow and
 * left out of that list fails on its own while the alarm reports success, because
 * {@code needs.*.result} only mentions the jobs it names.
 *
 * <p><b>And only as wide as the {@code results} string</b>, which is the coupling the move to a
 * reusable workflow introduced: the alarm no longer reads {@code needs} itself, it reads a string
 * the caller builds out of it. A job added to {@code needs} and left out of that string is a job
 * whose failure the alarm never sees - the same defect one level further in.
 *
 * <p><b>And only as wide as the triggers it answers to</b>, which had already gone wrong once:
 * {@code kanban-ci.yml} gained a {@code workflow_dispatch} trigger so a fix need not wait a day
 * for the cron, and the alarm's own condition named {@code schedule} alone, so a manual sweep ran
 * the suite and skipped the job that reports the result. Everything the alarm is made of was
 * therefore unreachable on purpose.
 *
 * <p>Like every guard here it does not skip when a file is missing. A guard that turns itself off
 * when it cannot find what it guards leaves the build green either way, and only one of those two
 * states is honest.
 */
class SweepAlarmCoverageTest {

    /** Tests run with {@code backend/} as the working directory, so the repository root is up one. */
    private static final Path WORKFLOWS = Path.of("..", ".github", "workflows");

    /** The shared implementation every alarm job delegates to. */
    private static final Path ALARM_WORKFLOW = WORKFLOWS.resolve("sweep-alarm.yml");

    /**
     * Every workflow that runs with nobody watching, and the job in it that speaks up.
     *
     * <p>This list is the one thing here that is maintained by hand. A new scheduled workflow
     * added and not listed is not caught - which is stated rather than solved, because the
     * alternative is guessing from the trigger block and silently covering workflows that were
     * never meant to alarm.
     */
    private static Stream<String[]> sweeps() {
        return Stream.of(
                new String[] { "kanban-ci.yml", "trunk-alarm" },
                new String[] { "kanban-cd.yml", "cd-alarm" },
                new String[] { "dast.yml", "scan-alarm" },
                new String[] { "dependency-scan.yml", "scan-alarm" },
                new String[] { "external-scan.yml", "scan-alarm" });
    }

    /**
     * The triggers that are a sweep of a ref nobody is already watching, rather than a push or a
     * pull request somebody just made and is looking at. These are the ones an alarm has to
     * answer to; everything else in a trigger block it must leave alone.
     */
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

    /**
     * The check the gap this guard was written over would have failed.
     *
     * <p>It is deliberately an allow-list assertion: the condition has to <em>name</em> every
     * sweep trigger. Writing the same rule as a deny-list ({@code event_name != 'push'}) would
     * read correctly today and silently stop covering the next trigger somebody adds, which is
     * the whole failure being guarded against - so the form is pinned, not only the meaning.
     */
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

    /** The inputs one alarm job forwards to the shared workflow. */
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

    /**
     * A workflow's trigger block.
     *
     * <p>{@code on} is a YAML 1.1 boolean, so the key parses as {@link Boolean#TRUE} rather than
     * as the string. Both are read so that a future parser resolving it the other way does not
     * turn every check above into one that quietly passes over a missing section.
     */
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
