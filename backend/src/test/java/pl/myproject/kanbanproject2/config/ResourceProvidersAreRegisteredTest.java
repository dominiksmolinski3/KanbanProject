package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceProvidersAreRegisteredTest {
    private static final Path TERRAFORM = Path.of("..", "terraform");

    private static final Path PROVIDERS = TERRAFORM.resolve("providers.tf");

    private static final Pattern RESOURCE_TYPE =
            Pattern.compile("(?m)^\\s*resource\\s+\"(azurerm_[a-z0-9_]+)\"");

    private static final Pattern REGISTRATION_LIST = Pattern.compile(
            "resource_providers_to_register\\s*=\\s*\\[(.*?)]", Pattern.DOTALL);

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    private static final Map<String, String> NAMESPACES = new LinkedHashMap<>() {{
        put("azurerm_container_app", "Microsoft.App");
        put("azurerm_eventgrid_", "Microsoft.EventGrid");
        put("azurerm_key_vault", "Microsoft.KeyVault");
        put("azurerm_log_analytics_", "Microsoft.OperationalInsights");
        put("azurerm_monitor_", "Microsoft.Insights");
        put("azurerm_application_insights", "Microsoft.Insights");
        put("azurerm_network_security_group", "Microsoft.Network");
        put("azurerm_postgresql_", "Microsoft.DBforPostgreSQL");
        put("azurerm_private_dns_", "Microsoft.Network");
        put("azurerm_private_endpoint", "Microsoft.Network");
        put("azurerm_managed_redis", "Microsoft.Cache");
        put("azurerm_resource_group", "Microsoft.Resources");
        put("azurerm_role_assignment", "Microsoft.Authorization");
        put("azurerm_storage_", "Microsoft.Storage");
        put("azurerm_subnet", "Microsoft.Network");
        put("azurerm_user_assigned_identity", "Microsoft.ManagedIdentity");
        put("azurerm_virtual_network", "Microsoft.Network");
    }};

    private static final Set<String> ALWAYS_REGISTERED =
            Set.of("Microsoft.Resources", "Microsoft.Authorization");

    @Test
    @DisplayName("every Azure service the Terraform declares is named in resource_providers_to_register")
    void everyNamespaceUsedIsRegistered() throws IOException {
        Set<String> used = namespacesUsed();
        Set<String> declared = namespacesDeclared();

        assertThat(declared)
                .as("a namespace used by a resource and missing from the provider block is an "
                        + "apply that dies with MissingSubscriptionRegistration against any "
                        + "subscription that has not happened to use that service before - which "
                        + "is every new environment, and was dev on the first delivery-report apply")
                .containsExactlyInAnyOrderElementsOf(used);
    }

    @Test
    @DisplayName("nothing is registered that no resource needs")
    void nothingSuperfluousIsRegistered() throws IOException {
        assertThat(namespacesDeclared())
                .as("a namespace nobody uses is the same dead configuration ConfigurationTest "
                        + "exists to catch one file over: it survives every build while meaning "
                        + "nothing, and the next reader takes it as evidence the service is in use")
                .isSubsetOf(namespacesUsed());
    }

    @Test
    @DisplayName("the control-plane namespaces are left out, so nothing asks ARM to register ARM")
    void theControlPlaneIsNotInTheList() throws IOException {
        assertThat(namespacesDeclared())
                .as("Microsoft.Resources and Microsoft.Authorization cannot be unregistered, so a "
                        + "registration for them is a call with nothing to do")
                .doesNotContainAnyElementsOf(ALWAYS_REGISTERED);
    }

    private static Set<String> namespacesUsed() throws IOException {
        Set<String> namespaces = new TreeSet<>();

        for (Path file : terraformFiles()) {
            Matcher matcher = RESOURCE_TYPE.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                String type = matcher.group(1);
                String namespace = namespaceOf(type).orElseThrow(() -> new AssertionError(
                        "no ARM namespace is recorded for the resource type '" + type + "' (in "
                                + file + "). Add it to NAMESPACES in this test and, unless it is "
                                + "already there, to resource_providers_to_register in "
                                + "terraform/providers.tf - a namespace this subscription has "
                                + "never used is a 409 MissingSubscriptionRegistration partway "
                                + "through an apply."));
                if (!ALWAYS_REGISTERED.contains(namespace)) {
                    namespaces.add(namespace);
                }
            }
        }

        assertThat(namespaces)
                .as("no azurerm resources were found under %s at all, so this guard is reading the "
                        + "wrong tree rather than passing", TERRAFORM)
                .isNotEmpty();
        return namespaces;
    }

    private static Optional<String> namespaceOf(String resourceType) {
        return NAMESPACES.entrySet().stream()
                .filter(entry -> resourceType.startsWith(entry.getKey()))
                .max(Map.Entry.comparingByKey(java.util.Comparator.comparingInt(String::length)))
                .map(Map.Entry::getValue);
    }

    private static Set<String> namespacesDeclared() throws IOException {
        String providers = Files.readString(existing(PROVIDERS), StandardCharsets.UTF_8);

        Matcher list = REGISTRATION_LIST.matcher(providers);
        assertThat(list.find())
                .as("terraform/providers.tf declares no resource_providers_to_register, so the "
                        + "provider's own defaults are the only thing registering anything - and "
                        + "they do not cover Microsoft.EventGrid, which is how this was found")
                .isTrue();

        Set<String> declared = new TreeSet<>();
        Matcher entries = QUOTED.matcher(list.group(1));
        while (entries.find()) {
            declared.add(entries.group(1));
        }
        return declared;
    }

    private static Iterable<Path> terraformFiles() throws IOException {
        try (Stream<Path> tree = Files.walk(existing(TERRAFORM))) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".tf"))
                    .toList();
        }
    }

    private static Path existing(Path path) {
        assertThat(path).as("%s has moved or gone", path).exists();
        return path;
    }
}
