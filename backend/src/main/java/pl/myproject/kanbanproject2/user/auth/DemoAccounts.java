package pl.myproject.kanbanproject2.user.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

// The accounts whose password is published on the sign-in screen: everyone who uses one is a
// stranger to everyone else using it.
@Component
public class DemoAccounts {

    private final Set<String> emails;

    public DemoAccounts(@Value("${app.demo.account-emails:}") List<String> emails) {
        this.emails = emails.stream()
                .map(String::trim)
                .filter(email -> !email.isEmpty())
                .map(email -> email.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isDemo(User user) {
        return user.getEmail() != null && emails.contains(user.getEmail().toLowerCase(Locale.ROOT));
    }
}
