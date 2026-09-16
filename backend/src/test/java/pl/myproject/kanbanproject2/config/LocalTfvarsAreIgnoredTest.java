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
 * A guard over the one coupling on the mail-configuration path whose failure is a committed
 * secret. Values that must never be committed - the ACS connection string, the captcha secret, a
 * workstation's own IP - live in {@code terraform/<env>.local.tfvars}, which {@code tf.sh} loads
 * and {@code .gitignore} keeps out of the repository; nothing checks the two name the same file.
 * That drift already cost something once: {@code .gitignore} named exactly one environment's file
 * while the pattern was documented for every environment, so following the pattern for prod staged
 * its ACS connection string and captcha secret for commit. The fix was a glob, and this test is
 * what keeps it one. It also pins the old {@code .auto.} spelling as still ignored, since Terraform
 * loads every {@code *.auto.tfvars} on every run regardless of {@code -var-file}, silently feeding
 * one environment's values into another's plan.
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
