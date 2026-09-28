package pl.myproject.kanbanproject2.user.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DemoAccountsMatchTheBannerTest {
    private static final Path BANNER = Path.of("..", "frontend", "src", "components", "DemoBanner.jsx");
    private static final Pattern BANNER_EMAIL = Pattern.compile("email:\\s*'([^']+)'");

    @Test
    @DisplayName("the server redacts exactly the accounts the sign-in banner publishes")
    void serverAndBannerNameTheSameAccounts() throws IOException {
        Set<String> banner = BANNER_EMAIL.matcher(Files.readString(BANNER)).results()
                .map(match -> match.group(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));

        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            properties.load(in);
        }
        Set<String> server = Arrays.stream(properties.getProperty("app.demo.account-emails", "").split(","))
                .map(String::trim)
                .filter(email -> !email.isEmpty())
                .map(email -> email.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(banner).as("demo accounts found in DemoBanner.jsx").isNotEmpty();
        assertThat(server)
                .as("a demo account the banner publishes and the server does not know about shows every "
                        + "visitor where the others sign in from; one the server lists and the banner does "
                        + "not is an ordinary account with its device list hidden")
                .isEqualTo(banner);
    }
}
