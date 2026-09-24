package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RedisPrivateEndpointReachableTest {
    private static final Path TERRAFORM = Path.of("..", "terraform");
    private static final Path ROOT_MODULE = TERRAFORM.resolve("main.tf");
    private static final Path NSG = TERRAFORM.resolve(Path.of("modules", "vnet", "nsg.tf"));
    private static final Path API_APP = TERRAFORM.resolve(Path.of("modules", "api_app", "main.tf"));

    private static final String MANAGED_REDIS_PORT = "10000";

    @Test
    @DisplayName("the Redis private endpoint still lives in the subnet this test reads the NSG of")
    void redisSitsInThePrivateEndpointSubnet() throws IOException {
        assertThat(block(read(ROOT_MODULE), "module \"redis\""))
                .as("modules/redis moved to another subnet, so the NSG rule asserted below is "
                        + "guarding the wrong one")
                .contains("private_endpoint_subnet_id = module.vnet.private_endpoint_subnet_id");
    }

    @Test
    @DisplayName("the API is told the Managed Redis port, not a classic cache's")
    void theApiConnectsOnTheManagedRedisPort() throws IOException {
        assertThat(read(API_APP))
                .contains("SECURITY_RATE_LIMIT_REDIS_PORT")
                .contains("tostring(var.redis_port)");
    }

    @Test
    @DisplayName("the private-endpoint NSG admits the backend on the Managed Redis port")
    void theNsgAdmitsRedisFromTheBackend() throws IOException {
        String nsg = block(read(NSG), "resource \"azurerm_network_security_group\" \"private_endpoints\"");

        assertThat(allowRuleFromBackendOn(nsg, MANAGED_REDIS_PORT))
                .as("without it every API call waits out the Redis connect timeout and the rate "
                        + "limiters fail open - a green apply, a healthy revision and a slow app")
                .isTrue();
    }

    @Test
    @DisplayName("control: the Key Vault rule is found by the same matcher")
    void theMatcherFindsAKnownRule() throws IOException {
        String nsg = block(read(NSG), "resource \"azurerm_network_security_group\" \"private_endpoints\"");

        assertThat(allowRuleFromBackendOn(nsg, "443")).isTrue();
        assertThat(allowRuleFromBackendOn(nsg, "6380")).isFalse();
    }

    private static boolean allowRuleFromBackendOn(String nsg, String port) {
        Matcher rule = Pattern.compile("security_rule\\s*\\{([^}]*)}").matcher(nsg);
        while (rule.find()) {
            String body = rule.group(1);
            if (body.matches("(?s).*access\\s*=\\s*\"Allow\".*")
                    && body.matches("(?s).*direction\\s*=\\s*\"Inbound\".*")
                    && body.matches("(?s).*source_address_prefix\\s*=\\s*local\\.backend_subnet_cidr.*")
                    && body.matches("(?s).*destination_port_range\\s*=\\s*\"" + port + "\".*")) {
                return true;
            }
        }
        return false;
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
