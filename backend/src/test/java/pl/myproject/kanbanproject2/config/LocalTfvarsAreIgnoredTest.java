package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A guard over the one coupling on the mail-configuration path whose failure is a committed secret.
 *
 * <p>The values that must never be committed - the ACS connection string, the captcha secret, the
 * alert address, a workstation's own IP for the Key Vault firewall - live in
 * {@code terraform/<env>.local.tfvars}, which {@code tf.sh} passes after the committed
 * {@code <env>.tfvars}. Two files decide that this is safe: {@code tf.sh} decides which filename is
 * loaded, and {@code .gitignore} decides which filename is kept out of the repository. Nothing
 * checks that they are the same filename.
 *
 * <p><b>That drift has already cost something once.</b> {@code .gitignore} named exactly one file,
 * {@code terraform/dev.local.auto.tfvars}, while the pattern was documented for every environment -
 * so doing MAIL-02 the way the finding itself instructs, for prod, staged a file holding the
 * production ACS connection string and captcha secret for commit. The fix was a glob; what keeps it
 * a glob is this test. A rename on one side and not the other leaves Terraform working perfectly,
 * {@code terraform fmt} and {@code validate} clean, both suites green, and the next
 * {@code git add -A} carrying a secret.
 *
 * <p>It also pins the absence of the old {@code .auto.} spelling as something still ignored.
 * Terraform loads every {@code *.auto.tfvars} in the working directory on every run, whatever
 * {@code -var-file} is passed, so one of those files supplies its values to every other
 * environment's plan - measurably: a {@code prod.tfvars} setting {@code env} alongside a
 * {@code dev.local.auto.tfvars} setting {@code env} and a second variable yields prod's
 * {@code env} and dev's second variable. {@code tf.sh} refuses to run while one exists, and it
 * stays ignored so that a leftover cannot be committed either.
 *
 * <p>Like every guard here it does not skip when a file is missing. A guard that turns itself off
 * when it cannot find what it guards leaves the build green either way, and only one of those two
 * states is honest.
 */
class LocalTfvarsAreIgnoredTest {

    /** Tests run with {@code backend/} as the working directory, so the repository root is up one. */
    private static final Path REPO_ROOT = Path.of("..");

    private static final Path GITIGNORE = REPO_ROOT.resolve(".gitignore");
    private static final Path TF_SH = REPO_ROOT.resolve(Path.of("terraform", "tf.sh"));

    @Test
    @DisplayName("tf.sh loads a local tfvars file, which is the thing that must be ignored")
    void tfShStillLoadsALocalOverrideFile() throws IOException {
        assertThat(read(TF_SH))
                .as("tf.sh no longer pairs a per-environment local override file, so either this "
                        + "guard is pointing at the wrong thing or secrets have moved somewhere "
                        + "nothing ignores")
                .contains("${env_name}.local.tfvars");
    }

    @Test
    @DisplayName(".gitignore covers every environment's local tfvars, not one named environment")
    void everyEnvironmentsLocalTfvarsIsIgnored() throws IOException {
        List<String> patterns = ignorePatterns();

        assertThat(patterns)
                .as("a per-environment pattern is what keeps prod's ACS connection string and "
                        + "captcha secret out of the repository; naming one environment is how it "
                        + "failed before")
                .contains("terraform/*.local.tfvars");
    }

    @Test
    @DisplayName("the .auto. spelling tf.sh refuses is still ignored, so a leftover cannot be committed")
    void theLegacyAutoSpellingIsStillIgnored() throws IOException {
        assertThat(ignorePatterns())
                .as("tf.sh refuses to run while one of these exists, but refusing to plan is not "
                        + "refusing to commit")
                .contains("terraform/*.local.auto.tfvars");

        assertThat(read(TF_SH))
                .as("nothing stops a *.local.auto.tfvars being auto-loaded into every "
                        + "environment's plan any more")
                .contains("*.local.auto.tfvars");
    }

    @Test
    @DisplayName("a saved plan is ignored, because tf.sh's own documented workflow writes one")
    void savedPlansAreIgnored() throws IOException {
        assertThat(ignorePatterns())
                .as("a saved plan holds the values it would write - the ACS connection string, the "
                        + "captcha secret, the Postgres password - in the clear, and `plan -out=` "
                        + "is the workflow tf.sh's own header documents, so the file appears by "
                        + "following the instructions rather than by going off them")
                .contains("terraform/*.tfplan");

        assertThat(read(TF_SH))
                .as("tf.sh no longer documents a saved plan, so either this guard is pointing at a "
                        + "workflow nobody uses or the apply path has moved")
                .contains("-out=");
    }

    /** Non-blank, non-comment lines, trimmed - which is what git itself reads them as. */
    private static List<String> ignorePatterns() throws IOException {
        return Files.readAllLines(exists(GITIGNORE), StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    private static String read(Path path) throws IOException {
        return Files.readString(exists(path), StandardCharsets.UTF_8);
    }

    private static Path exists(Path path) {
        assertThat(path).as("%s has moved or gone", path).exists();
        return path;
    }
}
