package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two halves of the sweep's claim, neither of which any other test here can see.
 *
 * <p>Every suite in this package mocks {@link TaskRepository}, so the claim query's text reaches
 * nothing that could object to it, and {@code QueryStringsResolveTest} deliberately skips native
 * queries - it compiles HQL and has no parser for SQL. So this is a string that runs for the first
 * time in a deployment, and what it says is worth pinning even though what it does is not checkable
 * from here.
 *
 * <p>Both halves fail silently and identically at one replica, which is the whole problem:
 *
 * <ul>
 *   <li><b>{@code FOR UPDATE SKIP LOCKED}.</b> Without it, two schedulers flip the same tasks,
 *       record the same expiries, and mail every assignee twice. With a plain {@code FOR UPDATE}
 *       the second sweep is correct and useless - it waits out the first one's mail run.</li>
 *   <li><b>A transaction around the sweep.</b> Row locks end with the transaction that took them.
 *       {@code TaskService} is {@code @Transactional} at the class level and the claim depends on
 *       it entirely; without it the lock is released at the end of its own statement and the claim
 *       is decoration. Nothing else in this repository would notice its removal, because every
 *       write here works perfectly well in Spring Data's own per-call transaction.</li>
 * </ul>
 */
class DeadlineSweepClaimTest {

    @Test
    @DisplayName("the sweep's claim still locks the rows it takes, and still skips locked ones")
    void theClaimStillSkipsLockedRows() {
        Query query = methodNamed("claimTasksCrossingDeadline").getAnnotation(Query.class);
        assertThat(query).as("claimTasksCrossingDeadline should still carry its query").isNotNull();
        assertThat(query.nativeQuery()).as("SKIP LOCKED has no JPQL spelling").isTrue();

        String sql = query.value().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");

        assertThat(sql)
                .as("without the lock, two sweeps mail the same overdue task to the same people")
                .contains("FOR UPDATE SKIP LOCKED");
        assertThat(sql)
                .as("the nullable column and the primitive field have to agree on what a null is, "
                        + "or the sweep selects rows it then declines to change, every half hour")
                .contains("COALESCE(EXPIRED, FALSE)");
    }

    @Test
    @DisplayName("the sweep runs in a transaction, which is the only thing holding its claim")
    void theSweepIsTransactional() {
        Method sweep = methodNamed(TaskService.class, "checkAllTasksDeadlines");

        // Both spellings, because Spring honours both and this service happens to carry Jakarta's.
        // Asserting only the Spring one would fail on a class that is perfectly transactional.
        assertThat(isTransactional(sweep) || isTransactional(TaskService.class))
                .as("a claim whose transaction ends with the select is a claim that holds nothing")
                .isTrue();
    }

    private static boolean isTransactional(java.lang.reflect.AnnotatedElement element) {
        return element.getAnnotation(Transactional.class) != null
                || element.getAnnotation(jakarta.transaction.Transactional.class) != null;
    }

    private static Method methodNamed(String name) {
        return methodNamed(TaskRepository.class, name);
    }

    private static Method methodNamed(Class<?> owner, String name) {
        return Arrays.stream(owner.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(owner.getSimpleName() + " has no " + name + " any more"));
    }
}
