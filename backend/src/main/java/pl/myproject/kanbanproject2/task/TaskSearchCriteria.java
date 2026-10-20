package pl.myproject.kanbanproject2.task;

import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

public record TaskSearchCriteria(String text,
                                 Set<String> labels,
                                 Set<Integer> assignees,
                                 Boolean completed,
                                 Instant deadlineFrom,
                                 Instant deadlineTo,
                                 int page,
                                 int size) {

    public static final int DEFAULT_PAGE_SIZE = 25;

    public static final int MAX_PAGE_SIZE = 100;

    static final char LIKE_ESCAPE = '!';

    public static TaskSearchCriteria of(String text,
                                        Set<String> labels,
                                        Set<Integer> assignees,
                                        Boolean completed,
                                        Instant deadlineFrom,
                                        Instant deadlineTo,
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

    private static String normalise(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

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

    private static final Instant UNFILTERED_INSTANT = Instant.EPOCH;

    String textOrPlaceholder() {
        String pattern = likePattern();
        return pattern == null ? "" : pattern;
    }

    boolean completedOrPlaceholder() {
        return completed != null && completed;
    }

    Instant deadlineFromOrPlaceholder() {
        return deadlineFrom == null ? UNFILTERED_INSTANT : deadlineFrom;
    }

    Instant deadlineToOrPlaceholder() {
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
