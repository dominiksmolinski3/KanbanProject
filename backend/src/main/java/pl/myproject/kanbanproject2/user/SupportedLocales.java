package pl.myproject.kanbanproject2.user;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class SupportedLocales {
    public static final String DEFAULT = "en";

    public static final Set<String> TAGS =
            Set.of("ar", "de", "en", "es", "fr", "it", "ja", "pl", "ru");

    private SupportedLocales() {
    }

    public static boolean isSupported(String tag) {
        return languageOf(tag) != null;
    }

    public static String normalise(String tag) {
        String language = languageOf(tag);
        return language == null ? DEFAULT : language;
    }

    public static Locale toLocale(String tag) {
        return Locale.forLanguageTag(normalise(tag));
    }

    public static String fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        for (String entry : header.split(",")) {
            String tag = entry.split(";")[0].trim();
            String language = languageOf(tag);
            if (language != null) {
                return language;
            }
        }
        return null;
    }

    private static String languageOf(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String[] parts = tag.trim().replace('_', '-').split("-");
        if (parts.length == 0) {
            return null;
        }
        String language = parts[0].toLowerCase(Locale.ROOT);
        return TAGS.contains(language) ? language : null;
    }

    public static Set<String> sorted() {
        return new LinkedHashSet<>(TAGS.stream().sorted().toList());
    }
}
