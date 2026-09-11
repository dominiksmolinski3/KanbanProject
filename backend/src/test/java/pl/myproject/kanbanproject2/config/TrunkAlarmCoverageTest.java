package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A guard over the one thing in {@code kanban-ci.yml} that can rot silently.
 *
 * <p>The workflow now runs on a schedule as well as on pushes and pull requests, because a push
 * made with the default {@code GITHUB_TOKEN} does not start a workflow run - and Dependabot's
 * auto-merge job merges with exactly that token. That is how a merge commit reached {@code main}
 * with {@code react} and {@code react-dom} on different versions and nothing ran the suite
 * against the result. The sweep closes that hole; the {@code trunk-alarm} job is what turns a red
 * sweep into something a person is told about.
 *
 * <p><b>The alarm is only as wide as its {@code needs} list.</b> A job added to this workflow and
 * left out of that list fails on its own and the alarm still reports success, because
 * {@code needs.*.result} only mentions the jobs it names. Nothing in YAML, in Actions, or in any
 * linter notices: the workflow is valid, the run is red in the tab, and the issue nobody opened is
 * the whole signal gone. That is the same shape as {@code DeadLetterAlertTest}'s coupling - a rule
 * living in two places, checked in one - and it is checked here for the same reason.
 *
 * <p>Like every guard here it does not skip when the file is missing. A guard that turns itself
 * off when it cannot find what it guards leaves the build green either way, and only one of those
 * two states is honest.
 */
class TrunkAlarmCoverageTest {

    /** Tests run with {@code backend/} as the working directory, so the repository root is up one. */
    private static final Path WORKFLOW = Path.of("..", ".github", "workflows", "kanban-ci.yml");

    private static final String ALARM_JOB = "trunk-alarm";

    @Test
    @DisplayName("the alarm waits on every other job in the workflow")
    void theAlarmCoversEveryJob() throws IOException {
        Map<String, Object> jobs = jobs();

        assertThat(jobs)
                .as("the job that reports a red trunk to a person has gone")
                .containsKey(ALARM_JOB);

        Set<String> everythingElse = new LinkedHashSet<>(jobs.keySet());
        everythingElse.remove(ALARM_JOB);

        assertThat(needsOf(jobs))
                .as("a job the alarm does not wait on can fail while the alarm reports success")
                .containsExactlyInAnyOrderElementsOf(everythingElse);
    }

    @Test
    @DisplayName("the workflow still runs on a schedule, which is the only trigger the alarm fires on")
    void theSweepIsStillScheduled() throws IOException {
        Map<String, Object> workflow = workflow();

        /*
         * `on` is a YAML 1.1 boolean, so the key parses as Boolean.TRUE rather than the string.
         * Both are checked so that a future parser resolving it the other way does not turn this
         * into a test that quietly passes over a missing section.
         */
        Object triggers = workflow.containsKey(Boolean.TRUE) ? workflow.get(Boolean.TRUE) : workflow.get("on");

        assertThat(triggers)
                .as("the workflow has no trigger block at all")
                .isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> on = (Map<String, Object>) triggers;

        assertThat(on)
                .as("without the sweep, a merge made with GITHUB_TOKEN runs no CI and nothing notices")
                .containsKey("schedule");
        assertThat(on)
                .as("the sweep cannot be run by hand to confirm a fix, so a red trunk waits a day")
                .containsKey("workflow_dispatch");
    }

    @Test
    @DisplayName("the alarm runs whatever its dependencies did, and only on the sweep")
    void theAlarmRunsOnFailureAndOnlyOnSchedule() throws IOException {
        Object condition = job(ALARM_JOB).get("if");

        assertThat(condition)
                .as("a job with no `if` is skipped the moment a dependency fails, which is exactly "
                        + "the case it exists for")
                .asString()
                .contains("always()")
                .contains("schedule");
    }

    @Test
    @DisplayName("the alarm may write issues, which is the whole of how it reaches anybody")
    void theAlarmCanOpenAnIssue() throws IOException {
        Object permissions = job(ALARM_JOB).get("permissions");

        assertThat(permissions)
                .as("the workflow-level default is read-only, so the job needs its own grant or "
                        + "`gh issue create` fails and the alarm is a red step nobody reads")
                .isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> grants = (Map<String, Object>) permissions;

        assertThat(grants).containsEntry("issues", "write");
    }

    private static List<String> needsOf(Map<String, Object> jobs) {
        @SuppressWarnings("unchecked")
        Map<String, Object> alarm = (Map<String, Object>) jobs.get(ALARM_JOB);
        Object needs = alarm.get("needs");

        assertThat(needs)
                .as("the alarm declares no dependencies, so it reports on nothing")
                .isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<String> declared = (List<String>) needs;
        return declared;
    }

    private static Map<String, Object> job(String name) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> job = (Map<String, Object>) jobs().get(name);
        assertThat(job).as("job '%s' is gone from the workflow", name).isNotNull();
        return job;
    }

    private static Map<String, Object> jobs() throws IOException {
        Object jobs = workflow().get("jobs");
        assertThat(jobs).as("the workflow declares no jobs").isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> declared = (Map<String, Object>) jobs;
        return declared;
    }

    private static Map<String, Object> workflow() throws IOException {
        assertThat(WORKFLOW)
                .as("the CI workflow this guard reads has moved or gone")
                .exists();

        try (Reader reader = Files.newBufferedReader(WORKFLOW, StandardCharsets.UTF_8)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new Yaml().loadAs(reader, Map.class);
            return parsed;
        }
    }
}
