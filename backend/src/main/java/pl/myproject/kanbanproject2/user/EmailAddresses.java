package pl.myproject.kanbanproject2.user;

import java.util.Locale;

public final class EmailAddresses {

    private EmailAddresses() {
    }

    public static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
