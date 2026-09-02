package pl.myproject.kanbanproject2.user;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The languages an account can be set to, and the one thing that decides what counts as one.
 *
 * <p>These are the nine directories under {@code frontend/public/locales} - the bundles
 * {@code i18next-http-backend} loads at runtime - and they are the same nine because a language
 * the screen speaks and the mail does not is the gap this exists to close.
 * {@code SupportedLocalesMatchClientTest} reads that directory and fails the build when the two
 * lists drift, which is the only thing that can: adding a tenth bundle is a directory and a
 * dropdown entry, neither of which any compiler connects to this file.
 *
 * <p>{@link #normalise} is deliberately forgiving in one direction and strict in the other.
 * Anything that names a supported language is taken - {@code "pl"}, {@code "PL"},
 * {@code "pl-PL"}, {@code "pl_PL"} all mean Polish, because a browser tag arrives in whichever
 * of those shapes the browser feels like - while anything else is rejected rather than silently
 * turned into English. The two callers want opposite things from that: signup falls back to
 * {@link #DEFAULT} for an unrecognised browser, and the account setting answers 400, because one
 * is a guess and the other is somebody's explicit choice.
 */
public final class SupportedLocales {

    /**
     * What an account gets when nothing better is known, and what {@code V11} backfilled onto every
     * account that existed before the column did - which is not a preference so much as a record
     * of what those accounts were already being sent.
     */
    public static final String DEFAULT = "en";

    /** The language subtags, and nothing regional - see {@link #normalise} for why. */
    public static final Set<String> TAGS =
            Set.of("ar", "de", "en", "es", "fr", "it", "ja", "pl", "ru");

    private SupportedLocales() {
    }

    /** Whether this is a tag an account may be set to, in any of the shapes a browser sends it. */
    public static boolean isSupported(String tag) {
        return languageOf(tag) != null;
    }

    /**
     * The stored form of a tag: its language subtag, lower-cased, or {@link #DEFAULT} when the tag
     * names no language this application has messages for.
     *
     * <p>Only the language is kept. Nine bundles with no regional variants between them means
     * {@code "de-AT"} and {@code "de-DE"} would select the same messages, and a column holding the
     * distinction anyway is a column that invites somebody to believe it is honoured.
     */
    public static String normalise(String tag) {
        String language = languageOf(tag);
        return language == null ? DEFAULT : language;
    }

    /** The stored tag as a {@link Locale}, for the message bundles to select on. */
    public static Locale toLocale(String tag) {
        return Locale.forLanguageTag(normalise(tag));
    }

    /**
     * The first tag in a browser's {@code Accept-Language} list that names a language we have, or
     * {@code null} when it names none.
     *
     * <p>Quality values are ignored on purpose: they order preferences a browser sends in
     * preference order anyway, and parsing them properly is more code than the one place this is
     * called for is worth. What it is called for is a better first guess at signup than
     * {@link #DEFAULT}, and it is overwritten the moment anybody says otherwise.
     */
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
        // A browser sends pl, pl-PL or, through some paths, pl_PL. All three name Polish.
        // Split first, then index: "-" splits to nothing at all, because Java drops trailing empty
        // parts and both of that string's parts are empty.
        String[] parts = tag.trim().replace('_', '-').split("-");
        if (parts.length == 0) {
            return null;
        }
        String language = parts[0].toLowerCase(Locale.ROOT);
        return TAGS.contains(language) ? language : null;
    }

    /** The tags in a stable order, for anything that has to present or compare the whole set. */
    public static Set<String> sorted() {
        return new LinkedHashSet<>(TAGS.stream().sorted().toList());
    }
}
