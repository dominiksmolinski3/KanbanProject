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
 * in this suite can see: every other suite mocks {@link OutboxEmailRepository}, and
 * {@code QueryStringsResolveTest} skips native queries deliberately, so this query text runs for the
 * first time in a real deployment. Losing any of the three is silent at one replica:
 *
 * <ul>
 *   <li><b>{@code FOR UPDATE SKIP LOCKED}.</b> Without the lock, two relays select the same rows and
 *       every message goes out twice; without {@code SKIP LOCKED}, the second relay blocks behind
 *       the first instead of working, draining at one replica's speed however many are running.</li>
 *   <li><b>A transaction around the claim.</b> The lock releases at transaction end, so splitting
 *       the select and the mark into two transactions leaves a window where both relays see
 *       {@code PENDING} - the lock does nothing.</li>
 *   <li><b>Public methods.</b> Spring's {@code AnnotationTransactionAttributeSource} considers
 *       public methods only; {@code @Transactional} on a package-private one is silently ignored.</li>
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
