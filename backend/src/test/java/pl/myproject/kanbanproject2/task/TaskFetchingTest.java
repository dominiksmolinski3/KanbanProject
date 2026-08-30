package pl.myproject.kanbanproject2.task;

import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import org.hibernate.annotations.BatchSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fetch strategy is a property of the mapping rather than of any one call, so it is asserted on the
 * mapping. These are the three shapes the board's read path depends on, and each of them is a
 * default that was silently wrong rather than something anyone chose:
 *
 * <ul>
 *   <li>{@code @ManyToOne} defaults to {@code EAGER}, so every listing fetched a column and a row
 *       per task whether or not anything read them.</li>
 *   <li>The collections default to {@code LAZY}, which is right, but {@link TaskMapper} touches
 *       {@code users} and {@code childTasks} on every task - one query each without a batch size.</li>
 *   <li>{@code getAllLabels} loaded the entire task table to fold a set of short strings.</li>
 * </ul>
 *
 * <p>None of this is observable from a unit test that mocks the repository, which is why the
 * assertions are about the annotations. Query counts belong to an integration test against a real
 * database, and there is not one yet.
 */
class TaskFetchingTest {

    private static Field field(String name) {
        try {
            return Task.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("Task has no field " + name, e);
        }
    }

    @Nested
    @DisplayName("the entity")
    class Entity {

        @Test
        @DisplayName("every to-one association is lazy, so a listing does not fetch what it will not read")
        void toOneAssociationsAreLazy() {
            for (String name : List.of("board", "column", "row", "parentTask")) {
                ManyToOne mapping = field(name).getAnnotation(ManyToOne.class);
                assertThat(mapping).as(name + " is @ManyToOne").isNotNull();
                assertThat(mapping.fetch())
                        .as(name + " must be LAZY - @ManyToOne defaults to EAGER")
                        .isEqualTo(FetchType.LAZY);
            }
        }

        @Test
        @DisplayName("every collection the mapper touches carries a batch size")
        void collectionsAreBatched() {
            for (String name : List.of("labels", "users", "childTasks", "subTasks")) {
                BatchSize batch = field(name).getAnnotation(BatchSize.class);
                assertThat(batch)
                        .as(name + " must be batched - the mapper touches it once per task")
                        .isNotNull();
                assertThat(batch.size()).isGreaterThan(1);
            }
        }
    }

    @Nested
    @DisplayName("the repository")
    class Repository {

        private static EntityGraph graphOn(String method, Class<?>... parameters) {
            try {
                Method found = TaskRepository.class.getDeclaredMethod(method, parameters);
                return found.getAnnotation(EntityGraph.class);
            } catch (NoSuchMethodException e) {
                throw new AssertionError("TaskRepository has no method " + method, e);
            }
        }

        @Test
        @DisplayName("every listing names the to-one associations the mapper reads")
        void listingsCarryAnEntityGraph() {
            List<EntityGraph> graphs = List.of(
                    graphOn("findByBoardOrderByIdAsc", pl.myproject.kanbanproject2.board.Board.class),
                    graphOn("findAllByDeadlineIsNotNull"),
                    graphOn("findByBoardAndDailyFocusTrue", pl.myproject.kanbanproject2.board.Board.class),
                    graphOn("findByBoardAndColumnAndRow",
                            pl.myproject.kanbanproject2.board.Board.class,
                            pl.myproject.kanbanproject2.layout.column.Column.class,
                            pl.myproject.kanbanproject2.layout.row.Row.class));

            for (EntityGraph graph : graphs) {
                assertThat(graph).as("listing without an @EntityGraph").isNotNull();
                assertThat(graph.attributePaths())
                        .as("lazy to-one associations have to be fetched by the query "
                                + "or they become one query per task")
                        .contains("column", "row", "parentTask");
            }
        }

        @Test
        @DisplayName("no listing joins a collection - two of them would multiply the rows")
        void listingsDoNotJoinCollections() {
            String[] paths = graphOn("findByBoardOrderByIdAsc",
                    pl.myproject.kanbanproject2.board.Board.class).attributePaths();

            assertThat(paths)
                    .as("users and childTasks are Sets, so Hibernate would allow the cartesian "
                            + "product rather than refusing it; @BatchSize handles them instead")
                    .doesNotContain("users", "childTasks", "subTasks", "labels");
        }
    }

    @Nested
    @DisplayName("the layout entities")
    class Layout {

        @Test
        @DisplayName("a column's and a swimlane's tasks are batched - the board renders all of them")
        void containerCollectionsAreBatched() {
            /*
             * ColumnMapper renders every task in every column, so an unbatched collection here is
             * one query per column and then a separate round of batching inside each of those
             * little lists. Measured against a board of 57 tasks in 8 columns, adding this took
             * GET /api/columns from 35 queries to 10.
             */
            for (Class<?> type : List.of(
                    pl.myproject.kanbanproject2.layout.column.Column.class,
                    pl.myproject.kanbanproject2.layout.row.Row.class)) {
                try {
                    BatchSize batch = type.getDeclaredField("tasks").getAnnotation(BatchSize.class);
                    assertThat(batch)
                            .as(type.getSimpleName() + ".tasks must be batched")
                            .isNotNull();
                    assertThat(batch.size()).isGreaterThan(1);
                } catch (NoSuchFieldException e) {
                    throw new AssertionError(type.getSimpleName() + " has no tasks field", e);
                }
            }
        }

        @Test
        @DisplayName("the board is a lazy to-one on everything that carries one")
        void boardIsLazyEverywhere() {
            for (Class<?> type : List.of(
                    Task.class,
                    pl.myproject.kanbanproject2.layout.column.Column.class,
                    pl.myproject.kanbanproject2.layout.row.Row.class)) {
                try {
                    ManyToOne mapping = type.getDeclaredField("board").getAnnotation(ManyToOne.class);
                    assertThat(mapping).as(type.getSimpleName() + ".board is @ManyToOne").isNotNull();
                    assertThat(mapping.fetch())
                            .as(type.getSimpleName() + ".board must be LAZY - every listing already "
                                    + "knows which board it asked for")
                            .isEqualTo(FetchType.LAZY);
                } catch (NoSuchFieldException e) {
                    throw new AssertionError(type.getSimpleName() + " has no board field", e);
                }
            }
        }
    }

    @Nested
    @DisplayName("the label vocabulary")
    class Labels {

        @Test
        @DisplayName("comes from a projection, not from loading every task")
        void labelsComeFromAProjection() {
            var repository = mock(TaskRepository.class);
            when(repository.findDistinctLabels(any())).thenReturn(Set.of("bug", "chore"));

            var service = TaskServiceTestSupport.withRepository(repository);

            assertThat(service.getAllLabels(TaskServiceTestSupport.caller(), null))
                    .containsExactlyInAnyOrder("bug", "chore");
            verify(repository).findDistinctLabels(TaskServiceTestSupport.board());
            verify(repository, never()).findAll();
        }
    }

    @Nested
    @DisplayName("the next position")
    class NextPosition {

        @Test
        @DisplayName("is a MAX in the database, not a fold over fetched rows")
        void positionIsAnAggregate() {
            var repository = mock(TaskRepository.class);
            when(repository.findMaxPosition(any(), any(), any())).thenReturn(java.util.Optional.of(7));
            when(repository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));

            var service = TaskServiceTestSupport.withRepository(repository);
            var created = service.addTask(TaskServiceTestSupport.caller(), null,
                    new CreateTaskRequest("Next", null, null, null, null, null, null));

            assertThat(created.position()).isEqualTo(8);
            verify(repository, never()).findByBoardAndColumnAndRow(any(), any(), any());
            verify(repository, never()).count();
        }

        @Test
        @DisplayName("an empty cell answers 1, because MAX over no rows is absent, not zero")
        void emptyCellStartsAtOne() {
            var repository = mock(TaskRepository.class);
            when(repository.findMaxPosition(any(), any(), any())).thenReturn(java.util.Optional.empty());
            when(repository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));

            var service = TaskServiceTestSupport.withRepository(repository);
            var created = service.addTask(TaskServiceTestSupport.caller(), null,
                    new CreateTaskRequest("First", null, null, null, null, null, null));

            assertThat(created.position()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("no lazy association is read outside a transaction - every service is transactional")
    void servicesAreTransactional() {
        List<Class<?>> services = List.of(
                TaskService.class,
                pl.myproject.kanbanproject2.board.BoardService.class,
                pl.myproject.kanbanproject2.layout.column.ColumnService.class,
                pl.myproject.kanbanproject2.layout.row.RowService.class,
                pl.myproject.kanbanproject2.user.UserService.class,
                pl.myproject.kanbanproject2.task.subtask.SubTaskService.class);

        List<String> untransactional = services.stream()
                .filter(type -> Arrays.stream(type.getAnnotations())
                        .noneMatch(a -> a.annotationType().getSimpleName().equals("Transactional")))
                .map(Class::getSimpleName)
                .toList();

        assertThat(untransactional)
                .as("open-in-view is false, so a lazy association read outside a transaction "
                        + "is a LazyInitializationException rather than an extra query")
                .isEmpty();
    }
}
