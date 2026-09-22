package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connection pool against the server it connects to.
 *
 * <p>Nothing in a running application says how many connections the database will give it, and
 * nothing in Terraform reads Hikari's defaults. Between those two silences sits the arithmetic
 * that decides whether the fleet can reach its own replica ceiling: HikariCP defaults
 * {@code maximum-pool-size} to 10 and {@code minimum-idle} to that same number, so five replicas
 * <em>hold</em> fifty connections at rest - against a {@code B_Standard_B1ms} whose
 * {@code max_connections} is 50 and which reserves 10 of them for superusers. Forty usable, fifty
 * asked for, and the first thing anybody would have seen is the {@code postgres_connections} alert
 * firing on refused connections, whose own description already names this mechanism.
 *
 * <p>So the numbers live in three files and this is the one place they meet:
 * {@code application.properties} names the two properties and their env vars,
 * {@code modules/api_app/main.tf} divides the fleet budget by {@code max_replicas} to reach the
 * per-replica share, and {@code modules/postgres/main.tf} records what each SKU actually has. The
 * root module compares the last two on every plan as well - but a Terraform {@code check} block
 * <em>warns</em> and does not fail, and it only runs where credentials do. This runs on every
 * build, which is where the rest of this repository's two-file rules are held.
 *
 * <p>The SKU table is measured rather than assumed for the one SKU that is deployed:
 * {@code az postgres flexible-server parameter show -n max_connections} against
 * {@code psql-dev-g1tuv} returns 50, and {@code superuser_reserved_connections} returns 10.
 */
class DatabasePoolBudgetTest {

    private static final Path REPO = Path.of("..");
    private static final Path APP_PROPERTIES = Path.of("src", "main", "resources", "application.properties");
    private static final Path TERRAFORM = REPO.resolve("terraform");
    private static final Path API_APP = TERRAFORM.resolve(Path.of("modules", "api_app", "main.tf"));
    private static final Path POSTGRES = TERRAFORM.resolve(Path.of("modules", "postgres", "main.tf"));
    private static final Path COMPOSE = REPO.resolve("docker-compose.yml");

    /** {@code "B_Standard_B1ms"      = 50} inside the SKU map. */
    private static final Pattern SKU_CONNECTIONS = Pattern.compile("\"((?:B|GP|MO)_[A-Za-z0-9_]+)\"\\s*=\\s*(\\d+)");
    private static final Pattern SUPERUSER_RESERVE =
            Pattern.compile("superuser_reserved_connections\\s*=\\s*(\\d+)");
    /** A {@code name = value} assignment in a tfvars file, numbers only - that is all this reads. */
    private static final Pattern TFVAR_NUMBER = Pattern.compile("(?m)^\\s*([a-z_]+)\\s*=\\s*(\\d+)\\s*$");
    private static final Pattern TFVAR_STRING = Pattern.compile("(?m)^\\s*([a-z_]+)\\s*=\\s*\"([^\"]*)\"\\s*$");

    /**
     * The defaults the module variables carry, for an environment whose tfvars file leaves one
     * out. Read from the Terraform rather than repeated here, because a default that changes on
     * one side and not the other is exactly the drift this test is for.
     */
    private static final Pattern ROOT_VARIABLE_NAME = Pattern.compile("^variable\\s+\"(\\w+)\"");
    private static final Pattern NUMERIC_DEFAULT = Pattern.compile("(?m)^\\s*default\\s*=\\s*(\\d+)\\s*$");

    // ------------------------------------------------------------------ the properties exist at all

    @Test
    @DisplayName("the pool size and the idle floor are both set, and both take an environment variable")
    void thePoolIsConfiguredAtAll() throws IOException {
        String properties = read(APP_PROPERTIES);

        assertThat(properties)
                .as("without an explicit maximum-pool-size every replica takes Hikari's default of "
                        + "ten, which is the state this test was written for")
                .contains("spring.datasource.hikari.maximum-pool-size=${DB_MAX_POOL_SIZE:");

        assertThat(properties)
                .as("minimum-idle left unset tracks maximum-pool-size, so the pool is not merely "
                        + "allowed to reach its ceiling - it sits there")
                .contains("spring.datasource.hikari.minimum-idle=${DB_MIN_IDLE:");
    }

    @Test
    @DisplayName("the per-replica pool size is divided out of a fleet-wide budget")
    void theModuleDividesTheBudgetByTheReplicaCeiling() throws IOException {
        String module = read(API_APP);

        assertThat(module)
                .as("DB_MAX_POOL_SIZE passed as a flat number would be a per-replica limit wearing "
                        + "a fleet-wide name - the mistake the attachment semaphore already made "
                        + "once and now divides by the same var.max_replicas")
                .contains("floor(var.db_connection_budget / var.max_replicas)");
    }

    // ------------------------------------------------------------------ the arithmetic per environment

    @Test
    @DisplayName("no environment's fleet can ask for more connections than its SKU has")
    void everyEnvironmentFitsItsServer() throws IOException {
        Map<String, Integer> connectionsBySku = connectionsBySku();
        int reserve = superuserReserve();
        Map<String, Integer> defaults = rootVariableDefaults();

        List<Path> environments = tfvarsFiles();
        assertThat(environments)
                .as("no tfvars files found under %s - this test is reading the wrong directory", TERRAFORM)
                .isNotEmpty();

        for (Path environment : environments) {
            String name = environment.getFileName().toString();
            Map<String, String> strings = stringSettings(environment);
            Map<String, Integer> numbers = numberSettings(environment);

            String sku = strings.getOrDefault("postgres_sku_name", "B_Standard_B1ms");
            assertThat(connectionsBySku)
                    .as("%s deploys %s and modules/postgres records no max_connections for it. "
                            + "Azure sizes the limit from the SKU and publishes it as no attribute, "
                            + "so an unlisted one is a guess", name, sku)
                    .containsKey(sku);

            int usable = connectionsBySku.get(sku) - reserve;
            int replicas = numbers.getOrDefault("api_max_replicas", defaults.get("api_max_replicas"));
            int budget = numbers.getOrDefault("api_db_connection_budget", defaults.get("api_db_connection_budget"));

            assertThat(budget)
                    .as("%s lets the API fleet hold %d connections; %s has %d less a superuser "
                            + "reserve of %d, so %d are available. This is the postgres_connections "
                            + "alert, pre-fired", name, budget, sku, connectionsBySku.get(sku), reserve, usable)
                    .isLessThanOrEqualTo(usable);

            assertThat(budget / replicas)
                    .as("%s divides a budget of %d across %d replicas, which floors the per-replica "
                            + "pool at one connection and stops the budget meaning what it says",
                            name, budget, replicas)
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("the local stack sizes its pool the way the deployment does")
    void theLocalStackMatchesTheDeployment() throws IOException {
        // Not because a local Postgres is short of connections - it has a hundred - but because
        // this stack is the shape the deployment is checked against, and a pool it never exercises
        // is a pool nothing catches before Azure. With the replicas profile up there really are
        // two JVMs on one server here too.
        assertThat(read(COMPOSE))
                .as("docker-compose leaves DB_MAX_POOL_SIZE to the property default, so the local "
                        + "stack runs a pool the deployment never uses")
                .contains("DB_MAX_POOL_SIZE:");
    }

    // ---------------------------------------------------------------------------------- sources

    /** The SKU-to-max_connections map in the Postgres module. */
    private static Map<String, Integer> connectionsBySku() throws IOException {
        Map<String, Integer> bySku = new LinkedHashMap<>();
        Matcher matcher = SKU_CONNECTIONS.matcher(read(POSTGRES));
        while (matcher.find()) {
            bySku.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }

        assertThat(bySku)
                .as("modules/postgres declares no max_connections_by_sku entries, which means this "
                        + "test has stopped reading the map rather than that the map is empty")
                .isNotEmpty();
        return bySku;
    }

    private static int superuserReserve() throws IOException {
        Matcher matcher = SUPERUSER_RESERVE.matcher(read(POSTGRES));
        assertThat(matcher.find())
                .as("modules/postgres no longer records superuser_reserved_connections, which is a "
                        + "fifth of this SKU's connections and the half of the arithmetic that is "
                        + "easiest to forget")
                .isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    /** The numeric defaults the root variables carry, for a tfvars file that sets neither. */
    private static Map<String, Integer> rootVariableDefaults() throws IOException {
        Map<String, Integer> defaults = new LinkedHashMap<>();
        // One block per `variable "..."`, split at the declarations rather than matched across
        // them: a variable whose default is a string or has none would otherwise let the pattern
        // run on and attach the *next* variable's number to this one's name.
        for (String block : read(TERRAFORM.resolve("variables.tf")).split("(?m)^(?=variable\\s)")) {
            Matcher name = ROOT_VARIABLE_NAME.matcher(block);
            Matcher value = NUMERIC_DEFAULT.matcher(block);
            if (name.find() && value.find()) {
                defaults.put(name.group(1), Integer.parseInt(value.group(1)));
            }
        }

        assertThat(defaults)
                .as("the root module declares no default for these, so an environment that sets "
                        + "neither could not be checked at all")
                .containsKeys("api_max_replicas", "api_db_connection_budget");
        return defaults;
    }

    private static List<Path> tfvarsFiles() throws IOException {
        try (var files = Files.list(TERRAFORM)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".tfvars")).sorted().toList();
        }
    }

    private static Map<String, Integer> numberSettings(Path tfvars) throws IOException {
        Map<String, Integer> settings = new LinkedHashMap<>();
        Matcher matcher = TFVAR_NUMBER.matcher(read(tfvars));
        while (matcher.find()) {
            settings.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
        }
        return settings;
    }

    private static Map<String, String> stringSettings(Path tfvars) throws IOException {
        Map<String, String> settings = new LinkedHashMap<>();
        Matcher matcher = TFVAR_STRING.matcher(read(tfvars));
        while (matcher.find()) {
            settings.put(matcher.group(1), matcher.group(2));
        }
        return settings;
    }

    private static String read(Path path) {
        try {
            assertThat(path).as("%s has moved or gone", path).isRegularFile();
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path.toAbsolutePath(), e);
        }
    }
}
