package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two-query search, and the three things about it that a single query would have got wrong.
 *
 * <p><b>The ids are paged and the rows are fetched separately</b>, so the {@code LIMIT} stays in
 * SQL rather than being applied in memory over a join whose rows multiply. That is only correct if
 * the second lookup is put back into the first one's order, which a set-based {@code IN} query does
 * not promise - so the ordering is asserted here rather than assumed.
 *
 * <p><b>The board is resolved before anything is searched</b>, which is what keeps a search from
 * being the one route that reaches across boards.
 *
 * <p><b>An empty page never becomes an empty {@code IN} list.</b> Paging past the end is an
 * ordinary thing for a client to do and has to be an empty answer, not a query the database
 * refuses.
 */
class TaskSearchServiceTest {

    private TaskRepository taskRepository;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        service = TaskServiceTestSupport.withRepository(taskRepository);
    }

    private static Task task(int id, String title) {
        var task = new Task();
        task.setId(id);
        task.setTitle(title);
        task.setBoard(TaskServiceTestSupport.board());
        return task;
    }

    private void matching(List<Integer> ids, long total, List<Task> rows) {
        when(taskRepository.findMatchingIds(any(), anyBoolean(), any(), anyBoolean(), anyBoolean(),
                anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(ids, PageRequest.of(0, TaskSearchCriteria.DEFAULT_PAGE_SIZE), total));
        when(taskRepository.findByIdIn(any())).thenReturn(rows);
    }

    private static TaskSearchCriteria criteria() {
        return TaskSearchCriteria.of(null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("returns the page's tasks with the total, so a client can tell short from last")
    void returnsAPageWithItsTotal() {
        matching(List.of(3, 1), 42, List.of(task(1, "one"), task(3, "three")));

        var results = service.searchTasks(TaskServiceTestSupport.caller(), null, criteria());

        assertThat(results.tasks()).hasSize(2);
        assertThat(results.totalTasks()).isEqualTo(42);
        assertThat(results.page()).isZero();
        assertThat(results.size()).isEqualTo(TaskSearchCriteria.DEFAULT_PAGE_SIZE);
        assertThat(results.totalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("the rows come back in the order the paged query chose, not the order they loaded in")
    void preservesTheQueryOrder() {
        // findByIdIn answers by id; the page asked for 3 before 1. Handing the rows straight
        // through would shuffle the result list, and only on boards long enough to page.
        matching(List.of(3, 1), 2, List.of(task(1, "one"), task(3, "three")));

        var results = service.searchTasks(TaskServiceTestSupport.caller(), null, criteria());

        assertThat(results.tasks()).extracting(TaskDto::id).containsExactly(3, 1);
    }

    @Test
    @DisplayName("a row the second query did not return is dropped rather than left as a null hole")
    void skipsRowsThatVanished() {
        matching(List.of(3, 1), 2, List.of(task(1, "one")));

        var results = service.searchTasks(TaskServiceTestSupport.caller(), null, criteria());

        assertThat(results.tasks()).extracting(TaskDto::id).containsExactly(1);
    }

    @Test
    @DisplayName("a page past the end is empty rather than a query with an empty IN list")
    void doesNotFetchAnEmptyPage() {
        when(taskRepository.findMatchingIds(any(), anyBoolean(), any(), anyBoolean(), anyBoolean(),
                anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
                any(Pageable.class)))
                .thenReturn(Page.empty(PageRequest.of(9, TaskSearchCriteria.DEFAULT_PAGE_SIZE)));

        var results = service.searchTasks(TaskServiceTestSupport.caller(), null,
                TaskSearchCriteria.of(null, null, null, null, null, null, 9, null));

        assertThat(results.tasks()).isEmpty();
        assertThat(results.totalPages()).isZero();
        verify(taskRepository, never()).findByIdIn(any());
    }

    @Test
    @DisplayName("the search is scoped to the resolved board, which is what keeps it off other boards")
    void searchesOnlyTheResolvedBoard() {
        matching(List.of(), 0, List.of());

        service.searchTasks(TaskServiceTestSupport.caller(), null, criteria());

        verify(taskRepository).findMatchingIds(eq(TaskServiceTestSupport.board()), anyBoolean(),
                any(), anyBoolean(), anyBoolean(), anyBoolean(), any(), anyBoolean(), any(),
                anyBoolean(), any(), anyBoolean(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("an unused facet is switched off by its flag, and a used one is passed through")
    void passesTheFacetsThrough() {
        matching(List.of(), 0, List.of());
        var ignoreLabels = ArgumentCaptor.forClass(Boolean.class);
        var labels = ArgumentCaptor.forClass(Collection.class);
        var ignoreAssignees = ArgumentCaptor.forClass(Boolean.class);

        service.searchTasks(TaskServiceTestSupport.caller(), null,
                TaskSearchCriteria.of("ship", Set.of("bug"), null, true, null, null, null, null));

        verify(taskRepository).findMatchingIds(any(), eq(false), eq("%ship%"), eq(false), eq(true),
                anyBoolean(), any(), anyBoolean(), any(),
                ignoreLabels.capture(), labels.capture(), ignoreAssignees.capture(), any(),
                any(Pageable.class));
        assertThat(ignoreLabels.getValue()).isFalse();
        assertThat(labels.getValue()).containsExactly("bug");
        assertThat(ignoreAssignees.getValue())
                .as("no assignee was asked for, so that half of the WHERE clause is switched off")
                .isTrue();
    }

    @Test
    @DisplayName("the page the caller asked for is the page the query is asked for")
    void passesThePageThrough() {
        matching(List.of(), 0, List.of());
        var pageable = ArgumentCaptor.forClass(Pageable.class);

        service.searchTasks(TaskServiceTestSupport.caller(), null,
                TaskSearchCriteria.of(null, null, null, null, null, null, 2, 10));

        verify(taskRepository).findMatchingIds(any(), anyBoolean(), any(), anyBoolean(), anyBoolean(),
                anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(), anyBoolean(), any(),
                pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }
}
