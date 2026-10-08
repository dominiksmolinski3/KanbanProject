package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringNsgTest {
    private static final Path TERRAFORM = Path.of("..", "terraform");
    private static final Path ROOT_MODULE = TERRAFORM.resolve("main.tf");
    private static final Path NSG = TERRAFORM.resolve(Path.of("modules", "vnet", "nsg.tf"));
    private static final Path MONITORING_VM = TERRAFORM.resolve(Path.of("modules", "monitoring_vm"));

    @Test
    @DisplayName("the monitoring subnet admits exactly one thing: HTTPS from the Container Apps subnet")
    void theOnlyWayInIsHttpsFromTheBackend() throws IOException {
        List<String> allowed = inboundAllowRules(nsg("monitoring"));

        assertThat(allowed)
                .as("a second inbound allow rule on the monitoring subnet is a second way in - port 22 "
                        + "or an admin address is exactly what this design removed")
                .hasSize(1);
        assertThat(allowed.getFirst())
                .containsPattern("source_address_prefix\\s*=\\s*local\\.backend_subnet_cidr")
                .containsPattern("destination_port_range\\s*=\\s*\"443\"");
    }

    @Test
    @DisplayName("the monitoring VM has no public IP")
    void theVmHasNoPublicAddress() throws IOException {
        try (var files = Files.list(MONITORING_VM)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".tf")).toList()) {
                assertThat(read(file))
                        .as("%s gives the VM an internet address; it is reached through Run Command and "
                                + "the Container Apps subnet only", file.getFileName())
                        .doesNotContain("azurerm_public_ip")
                        .doesNotContain("public_ip_address_id");
            }
        }
    }

    @Test
    @DisplayName("the VM sits in the subnet whose NSG this test reads")
    void theVmSitsInTheMonitoringSubnet() throws IOException {
        assertThat(block(read(ROOT_MODULE), "module \"monitoring_vm\""))
                .containsPattern("subnet_id\\s*=\\s*module\\.vnet\\.monitoring_subnet_id");
    }

    @Test
    @DisplayName("the VM can reach Key Vault, where it reads its credentials and writes its CA")
    void theVmReachesKeyVault() throws IOException {
        assertThat(inboundAllowRules(nsg("private_endpoints")))
                .as("without it the first converge fails reading its credentials")
                .anyMatch(rule -> rule.matches("(?s).*source_address_prefix\\s*=\\s*local\\.monitoring_subnet_cidr.*")
                        && rule.matches("(?s).*destination_port_range\\s*=\\s*\"443\".*"));
    }

    @Test
    @DisplayName("control: the backend NSG's three allow rules are all found")
    void theMatcherCountsAKnownNsg() throws IOException {
        assertThat(inboundAllowRules(nsg("backend"))).hasSize(3);
    }

    private static String nsg(String name) throws IOException {
        return block(read(NSG), "resource \"azurerm_network_security_group\" \"" + name + "\"");
    }

    private static List<String> inboundAllowRules(String nsg) {
        List<String> rules = new ArrayList<>();
        Matcher rule = Pattern.compile("security_rule\\s*\\{([^}]*)}").matcher(nsg);
        while (rule.find()) {
            String body = rule.group(1);
            if (body.matches("(?s).*access\\s*=\\s*\"Allow\".*") && body.matches("(?s).*direction\\s*=\\s*\"Inbound\".*")) {
                rules.add(body);
            }
        }
        return rules;
    }

    private static String block(String source, String header) {
        int start = source.indexOf(header);
        assertThat(start).as("%s not found", header).isNotNegative();
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced braces after " + header);
    }

    private static String read(Path path) throws IOException {
        assertThat(path).exists();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
