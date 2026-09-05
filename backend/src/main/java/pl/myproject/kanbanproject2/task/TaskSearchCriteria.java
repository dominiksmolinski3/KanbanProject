package pl.myproject.kanbanproject2.task;

import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * What a caller asked to find, normalised once so the service and the query agree about it.
 *
 * <p>Every field is optional and an absent one is not a filter - so criteria with nothing set match
 * the whole board, which is what an empty search box should do. The facets combine with AND across
 * kinds and OR within one: a search for two labels and one assignee finds the tasks that carry
 * <em>either</em> label <em>and</em> have that person on them. That is how a person reads a row of
 * filter chips, and the alternative - AND within a facet - makes a second chip almost always narrow
 * the result to nothing.
 *
 * <p><b>The page size is bounded here rather than clamped.</b> PERF-02 asked for a decision about
 * what "large enough" means before somebody found out the hard way; this is it. {@value
 * #DEFAULT_PAGE_SIZE} is a screenful, {@value #MAX_PAGE_SIZE} is the most a caller may ask for, and
 * asking for more is a {@code 400} rather than a silent {@value #MAX_PAGE_SIZE}. Serving something
 * other than what was asked for is how a client ends up paging through a list it believes it has
 * already read.
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
     * The most one request may ask for.
     *
     * <p>A hundred rows is far more than a person reads at once and still a bounded amount of work
     * for the two queries behind it. The point of the ceiling is not the rendering cost - it is
     * that without one, {@code size} is a caller-chosen multiplier on how much of the database a
     * single request can make the server assemble.
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * The escape character for the {@code LIKE} pattern.
     *
     * <p>Not a backslash: this has to survive being written as a JPQL string literal next to the
     * pattern it applies to, and a backslash in a literal is a quoting question in every layer it
     * passes through. An exclamation mark has no meaning to either JPQL or SQL and needs no
     * quoting.
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
     * The free-text filter as a {@code LIKE} pattern, or {@code null} for "no text filter".
     *
     * <p><b>The wildcards a person typed are escaped rather than honoured.</b> Searching for
     * {@code 100%} without this builds the pattern {@code %100%%}, which matches every task on the
     * board and reads as a search that has quietly stopped working; {@code _} is the same mistake
     * one character wide. Lower-cased here because the query compares against a lower-cased title -
     * doing it in the pattern is one call rather than one per row.
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

    /*
     * Binds for a filter that is switched off, and the reason none of them may be null.
     *
     * Every optional filter in the search query is a boolean flag plus a value, rather than the
     * `:param IS NULL OR ...` form used elsewhere in this repository. That form does not survive
     * contact with PostgreSQL here: a parameter whose *only* appearance is `? IS NULL` gives the
     * planner nothing to infer a type from, and the query dies at runtime with
     * `could not determine data type of parameter $7`. It works in `findMaxPosition` only because
     * every parameter there is also compared against a typed column in the same clause.
     *
     * Nothing above sees it, either: the mapping-level guard compiles HQL rather than running the
     * SQL, and every unit test here mocks the repository. It was found by running a search against
     * a real database and by nothing else.
     *
     * So each of these hands back something typed and non-null when its facet is off. What that
     * something is cannot matter, because the flag beside it already makes the whole clause true.
     */
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
