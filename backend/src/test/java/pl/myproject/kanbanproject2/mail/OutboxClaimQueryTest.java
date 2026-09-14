package pl.myproject.kanbanproject2.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three things that make the outbox safe at more than one replica, none of which any other test
 * in this suite can see.
 *
 * <p>Every suite here mocks {@link OutboxEmailRepository}, so the claim query's text reaches nothing
 * that could object to it. {@code QueryStringsResolveTest} compiles the hand-written HQL and skips
 * native queries deliberately - it has no parser for SQL and Hibernate would need a database to
 * check one. So the query is a string that runs for the first time in a deployment, and what it
 * <em>says</em> is worth pinning even though what it <em>does</em> is not checkable from here.
 *
 * <p>Losing any of the three is silent and looks like nothing at one replica, which is the whole
 * problem:
 *
 * <ul>
 *   <li><b>{@code FOR UPDATE SKIP LOCKED}.</b> Without the lock, two relays select the same rows
 *       and every message goes out twice. Without {@code SKIP LOCKED}, the second relay blocks
 *       behind the first instead of working - correct, and a queue that drains at one replica's
 *       speed however many are running.</li>
 *   <li><b>A transaction around the claim.</b> The lock is released when the transaction ends, so
 *       a select and a mark in two transactions leave a window in which both relays see
 *       {@code PENDING}. The lock would be doing nothing at all.</li>
 *   <li><b>Public methods.</b> Spring's {@code AnnotationTransactionAttributeSource} considers
 *       public methods only; {@code @Transactional} on a package-private one is ignored without a
 *       word, which is the previous point with no code change to notice.</li>
 * </ul>
 */
class OutboxClaimQueryTest {

    @Test
    @DisplayName("the claim query still locks the rows it takes, and still skips locked ones")
    void theClaimStillSkipsLockedRows() {
        String sql = claimQuery().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");

        assertThat(sql)
                .as("without FOR UPDATE two relays take the same rows and mail everything twice")
                .contains("FOR UPDATE SKIP LOCKED");
        assertThat(sql)
                .as("an unbounded claim is one relay holding the whole backlog")
                .contains("LIMIT :LIMIT");
    }

    @Test
    @DisplayName("claiming happens inside a transaction, on a method Spring will actually proxy")
    void claimingIsTransactionalAndProxyable() {
        for (String name : new String[]{"claimDue", "reclaimLapsed"}) {
            Method method = methodNamed(OutboxClaimer.class, name);

            assertThat(method.getAnnotation(Transactional.class))
                    .as("%s: the select and the mark have to commit together, or the lock guards nothing", name)
                    .isNotNull();
            assertThat(Modifier.isPublic(method.getModifiers()))
                    .as("%s: @Transactional on a non-public method is silently ignored", name)
                    .isTrue();
        }
    }

    private static String claimQuery() {
        Query query = methodNamed(OutboxEmailRepository.class, "claimBatch").getAnnotation(Query.class);
        assertThat(query).as("claimBatch should still carry its hand-written query").isNotNull();
        assertThat(query.nativeQuery()).as("SKIP LOCKED has no JPQL spelling").isTrue();
        return query.value();
    }

    private static Method methodNamed(Class<?> owner, String name) {
        return Arrays.stream(owner.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(owner.getSimpleName() + " has no " + name + " any more"));
    }
}
