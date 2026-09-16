package pl.myproject.kanbanproject2.task;

import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * What a caller asked to find, normalised once so the service and the query agree about it. Every
 * field is optional, and facets combine with AND across kinds but OR within one — two labels and
 * one assignee finds tasks with either label <em>and</em> that assignee, the way a row of filter
 * chips reads. The page size is bounded rather than clamped (PERF-02): {@value #DEFAULT_PAGE_SIZE}
 * default, {@value #MAX_PAGE_SIZE} max, and asking for more is a {@code 400} rather than a silent
 * clamp that would leave a client paging through a list it believes it already read.
 */
public record TaskSearchCriteria(String text,
                                 Set<String> labels,
                                 Set<Integer> assignees,
                                 Boolean completed,
                                 LocalDateTime deadlineFrom,
                                 LocalDateTime deadlineTo,
                                 int page,
                                 int size) {

    /** A screenful of results, and what a caller that says nothing about paging gets. */
    public static final int DEFAULT_PAGE_SIZE = 25;

    /**
     * The most one request may ask for — not for the rendering cost, but because without a
     * ceiling, {@code size} is a caller-chosen multiplier on how much of the database a single
     * request can make the server assemble.
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * The escape character for the {@code LIKE} pattern. Not a backslash, since that's a quoting
     * question in a JPQL string literal; an exclamation mark needs no quoting in JPQL or SQL.
     */
    static final char LIKE_ESCAPE = '!';

    /**
     * Builds one from what arrived on the query string, filling in the defaults and refusing what
     * cannot be served.
     */
    public static TaskSearchCriteria of(String text,
                                        Set<String> labels,
                                        Set<Integer> assignees,
                                        Boolean completed,
                                        LocalDateTime deadlineFrom,
                                        LocalDateTime deadlineTo,
                                        Integer page,
                                        Integer size) {
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (resolvedPage < 0) {
            throw invalid("page must not be negative");
        }
        if (resolvedSize < 1 || resolvedSize > MAX_PAGE_SIZE) {
            throw invalid("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (deadlineFrom != null && deadlineTo != null && deadlineFrom.isAfter(deadlineTo)) {
            throw invalid("deadlineFrom must not be after deadlineTo");
        }
        return new TaskSearchCriteria(
                normalise(text),
                labels == null ? Set.of() : Set.copyOf(labels),
                assignees == null ? Set.of() : Set.copyOf(assignees),
                completed,
                deadlineFrom,
                deadlineTo,
                resolvedPage,
                resolvedSize);
    }

    /** Blank is the same as absent - an empty search box is not a filter that matches nothing. */
    private static String normalise(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The free-text filter as a {@code LIKE} pattern, or {@code null} for none. Wildcards a person
     * typed are escaped rather than honoured — without it, searching {@code 100%} matches every
     * task on the board. Lower-cased here, since doing it once in the pattern beats once per row.
     */
    String likePattern() {
        if (text == null) {
            return null;
        }
        var escaped = new StringBuilder(text.length() + 8);
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == LIKE_ESCAPE || c == '%' || c == '_') {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(c);
        }
        return "%" + escaped + "%";
    }

    // Binds for a filter that's switched off. Each is a boolean flag plus a typed, non-null value
    // rather than the `:param IS NULL OR ...` form used elsewhere, since a parameter appearing only
    // as `? IS NULL` gives PostgreSQL nothing to infer a type from and fails at runtime — found
    // only by running a search against a real database. What the value is doesn't matter, since the
    // flag beside it already makes the whole clause true.
    private static final LocalDateTime UNFILTERED_INSTANT = LocalDateTime.of(1970, 1, 1, 0, 0);

    String textOrPlaceholder() {
        String pattern = likePattern();
        return pattern == null ? "" : pattern;
    }

    boolean completedOrPlaceholder() {
        return completed != null && completed;
    }

    LocalDateTime deadlineFromOrPlaceholder() {
        return deadlineFrom == null ? UNFILTERED_INSTANT : deadlineFrom;
    }

    LocalDateTime deadlineToOrPlaceholder() {
        return deadlineTo == null ? UNFILTERED_INSTANT : deadlineTo;
    }

    Set<String> labelsOrPlaceholder() {
        return labels.isEmpty() ? Set.of("") : labels;
    }

    Set<Integer> assigneesOrPlaceholder() {
        return assignees.isEmpty() ? Set.of(-1) : assignees;
    }

    private static GlobalException invalid(String message) {
        return new GlobalException(ExceptionIdentifier.INVALID_SEARCH, message);
    }
}
