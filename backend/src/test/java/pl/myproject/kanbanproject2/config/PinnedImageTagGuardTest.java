package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard over the check that stops an apply deploying a commit nobody looked at.
 *
 * {@code app_image_tag} is required and must be an immutable commit SHA, because the tag is the
 * only part of the container template that changes between releases - a mutable one renders an
 * identical template, computes no diff, and rolls no revision, so the environment reports itself
 * converged while serving whatever it served before. That much was already written down.
 *
 * What was not caught is the failure that actually happened: the pin was set correctly, once, and
 * then nobody moved it. It sat on one commit while roughly 25 pull requests merged, and the apply
 * meant to ship a broker relay rolled its Terraform faithfully onto a container running week-old
 * code. Nothing was wrong with the tag, the plan, the apply or the revision, and every instrument
 * here agreed the environment matched its configuration - because it did.
 *
 * {@code tf.sh} refuses that apply now, and three things about the refusal are invisible to
 * everything else:
 *
 * <ul>
 *   <li><b>It has to run on {@code apply} and nothing else.</b> A {@code plan} changes nothing and
 *       a {@code destroy} has no image to be stale.</li>
 *   <li><b>It has to run before {@code terraform init}</b>, which reaches the remote backend.
 *       A refusal after a round trip to Azure is slower and no more correct.</li>
 *   <li><b>Its escape hatch has to be this script's flag, not Terraform's.</b> Passed through,
 *       {@code --allow-stale-image} stops {@code terraform apply} with an unrecognised argument -
 *       which reads as the tool being broken rather than as an acknowledgement being offered.</li>
 * </ul>
 *
 * Same shape as {@link LocalTfvarsAreIgnoredTest}, which reads the same script for the same kind of
 * reason.
 */
class PinnedImageTagGuardTest {

    /** Tests run with {@code backend/} as the working directory, so the repository root is up one. */
    private static final Path REPO = Path.of("..");

    private static final Path TF_SH = REPO.resolve(Path.of("terraform", "tf.sh"));
    private static final Path TF_README = REPO.resolve(Path.of("terraform", "README.md"));

    private static final String CHECK = "check_pinned_image_is_current";
    private static final String FLAG = "--allow-stale-image";

    @Test
    @DisplayName("an apply is checked, and checked before anything reaches the backend")
    void theCheckRunsOnApplyAndRunsFirst() throws IOException {
        String script = read(TF_SH);

        assertThat(script)
                .as("tf.sh no longer checks the pinned image at all, so an apply can once again "
                        + "roll the Terraform onto a container running a commit nobody has looked "
                        + "at, and report success")
                .contains(CHECK + "()");

        int call = script.indexOf("  " + CHECK + " \"$@\"");
        assertThat(call)
                .as("the check is defined but never called, which is worse than not having it - "
                        + "the reasoning reads as enforcement and enforces nothing")
                .isGreaterThan(0);

        int init = script.indexOf("terraform init -reconfigure");
        assertThat(init).as("tf.sh no longer runs `terraform init`").isGreaterThan(0);

        assertThat(call)
                .as("the pinned-image check runs after `terraform init`, so a refusal costs a "
                        + "round trip to the remote backend first")
                .isLessThan(init);

        assertThat(script.substring(0, call))
                .as("the check is not gated on the subcommand, so it would refuse a `plan` (which "
                        + "changes nothing) or a `destroy` (which has no image to be stale)")
                .contains("[ \"$subcommand\" = \"apply\" ]");
    }

    @Test
    @DisplayName("the acknowledgement is tf.sh's own flag, and never reaches terraform")
    void theFlagIsStrippedFromTheArguments() throws IOException {
        String script = read(TF_SH);

        assertThat(script)
                .as("the refusal offers `%s` as the way through; nothing in tf.sh reads it, so "
                        + "following the instruction stops terraform with an unrecognised argument",
                        FLAG)
                .contains("\"$arg\" = \"" + FLAG + "\"");

        int strip = script.indexOf("allow_stale_image=1");
        int subcommand = script.indexOf("subcommand=$1");
        assertThat(strip)
                .as("the flag is read after the subcommand is taken off the arguments, so it is "
                        + "either mistaken for one or passed straight through to terraform")
                .isGreaterThan(0)
                .isLessThan(subcommand);
    }

    @Test
    @DisplayName("the rule tf.sh enforces is the rule the README states")
    void theScriptAndTheReadmeAgree() throws IOException {
        assertThat(read(TF_SH))
                .as("tf.sh no longer requires a 40-character commit SHA, so `latest` - the exact "
                        + "value that once left an environment reporting itself converged on an "
                        + "eight-day-old image - is accepted again")
                .contains("'^[0-9a-f]{40}$'");

        String readme = read(TF_README);

        assertThat(readme)
                .as("the README no longer says the tag must be immutable, so the refusal in tf.sh "
                        + "is a rule with nowhere to read about it")
                .contains("immutable");

        assertThat(readme)
                .as("`%s` is the only way past a refusal and the README does not mention it, so "
                        + "the documented workflow and the tool disagree about what an operator "
                        + "with a legitimately older pin is supposed to do", FLAG)
                .contains(FLAG);
    }

    private static String read(Path path) throws IOException {
        assertThat(path).as("%s has moved or gone", path).isRegularFile();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
