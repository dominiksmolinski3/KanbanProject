package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a search request means before it reaches the database.
 *
 * <p>Two of these are worth more than they look. <b>The wildcard escaping</b> is the difference
 * between searching for {@code 100%} and matching the whole board - a bug with no error and no
 * symptom other than a search that quietly stops narrowing anything. And <b>the page-size
 * refusal</b> is a decision rather than an implementation detail: PERF-02 asked for a number, and
 * the thing being pinned here is that asking for more than it is answered rather than silently
 * clamped.
 */
class TaskSearchCriteriaTest {

    private static TaskSearchCriteria criteria(String text) {
        return TaskSearchCriteria.of(text, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("nothing set is not a filter, so an empty search box matches the whole board")
    void emptyCriteriaFilterNothing() {
        var criteria = TaskSearchCriteria.of(null, null, null, null, null, null, null, null);

        assertThat(criteria.text()).isNull();
        assertThat(criteria.likePattern()).isNull();
        assertThat(criteria.labels()).isEmpty();
        assertThat(criteria.assignees()).isEmpty();
        assertThat(criteria.completed()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("a blank search term is the same as none at all")
    void blankTextIsNoFilter(String blank) {
        assertThat(criteria(blank).likePattern()).isNull();
    }

    @Test
    @DisplayName("a plain term becomes a lower-cased contains pattern")
    void buildsAContainsPattern() {
        assertThat(criteria("Deploy").likePattern()).isEqualTo("%deploy%");
    }

    @Test
    @DisplayName("a percent sign in the term is escaped, or the search matches everything")
    void escapesPercent() {
        assertThat(criteria("100%").likePattern())
                .as("unescaped this is %100%%, which every title matches")
                .isEqualTo("%100!%%");
    }

    @Test
    @DisplayName("an underscore is escaped too - the same mistake, one character wide")
    void escapesUnderscore() {
        assertThat(criteria("a_b").likePattern()).isEqualTo("%a!_b%");
    }

    @Test
    @DisplayName("the escape character escapes itself, so a term containing it still means itself")
    void escapesTheEscapeCharacter() {
        assertThat(criteria("wow!").likePattern()).isEqualTo("%wow!!%");
    }

    @Test
    @DisplayName("an unused collection facet still binds something, because an empty IN is not a skip")
    void placeholdersForUnusedFacets() {
        var criteria = TaskSearchCriteria.of(null, null, null, null, null, null, null, null);

        assertThat(criteria.labelsOrPlaceholder()).isNotEmpty();
        assertThat(criteria.assigneesOrPlaceholder()).isNotEmpty();
    }

    @Test
    @DisplayName("a used facet is passed through as itself")
    void usedFacetsAreNotReplaced() {
        var criteria = TaskSearchCriteria.of(null, Set.of("bug"), Set.of(4), null, null, null, null, null);

        assertThat(criteria.labelsOrPlaceholder()).containsExactly("bug");
        assertThat(criteria.assigneesOrPlaceholder()).containsExactly(4);
    }

    @Test
    @DisplayName("paging defaults to the first screenful when the caller says nothing")
    void defaultsThePage() {
        var criteria = TaskSearchCriteria.of(null, null, null, null, null, null, null, null);

        assertThat(criteria.page()).isZero();
        assertThat(criteria.size()).isEqualTo(TaskSearchCriteria.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("a page size over the ceiling is refused rather than quietly reduced to it")
    void refusesAnOversizedPage() {
        assertThatThrownBy(() -> TaskSearchCriteria.of(null, null, null, null, null, null, 0,
                TaskSearchCriteria.MAX_PAGE_SIZE + 1))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .as("clamping would have the caller page through rows it thinks it has read")
                .isEqualTo(ExceptionIdentifier.INVALID_SEARCH);
    }

    @Test
    @DisplayName("the ceiling itself is allowed - it is a limit, not a limit minus one")
    void allowsTheCeiling() {
        assertThat(TaskSearchCriteria.of(null, null, null, null, null, null, 0,
                TaskSearchCriteria.MAX_PAGE_SIZE).size())
                .isEqualTo(TaskSearchCriteria.MAX_PAGE_SIZE);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -25})
    @DisplayName("a page size below one is refused, because it can only be a mistake")
    void refusesAnEmptyPage(int size) {
        assertThatThrownBy(() -> TaskSearchCriteria.of(null, null, null, null, null, null, 0, size))
                .isInstanceOf(GlobalException.class);
    }

    @Test
    @DisplayName("a negative page number is refused")
    void refusesANegativePage() {
        assertThatThrownBy(() -> TaskSearchCriteria.of(null, null, null, null, null, null, -1, null))
                .isInstanceOf(GlobalException.class);
    }

    @Test
    @DisplayName("a deadline window that ends before it starts is refused rather than answered empty")
    void refusesABackwardsDeadlineWindow() {
        var later = LocalDateTime.of(2026, 6, 1, 12, 0);
        var earlier = LocalDateTime.of(2026, 5, 1, 12, 0);

        assertThatThrownBy(() -> TaskSearchCriteria.of(null, null, null, null, later, earlier, null, null))
                .as("an empty result is indistinguishable from a board with no matches")
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.INVALID_SEARCH);
    }

    @Test
    @DisplayName("a window that starts and ends at the same instant is a window, not a mistake")
    void allowsAnInstantWindow() {
        var moment = LocalDateTime.of(2026, 6, 1, 12, 0);

        assertThat(TaskSearchCriteria.of(null, null, null, null, moment, moment, null, null))
                .isNotNull();
    }
}
