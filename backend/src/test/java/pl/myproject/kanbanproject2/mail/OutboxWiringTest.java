package pl.myproject.kanbanproject2.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import pl.myproject.kanbanproject2.config.EmailConfiguration;
import pl.myproject.kanbanproject2.service.EmailSender;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A build-time guard over a wiring that fails at startup rather than at compile time.
 *
 * <p>There are two {@code EmailSender} beans now, which is one more than Spring will resolve on
 * its own. {@link OutboxEmailSender} carries {@code @Primary} so everything above the queue gets
 * it, and {@link OutboxRelay} names {@code mailTransport} so it gets the real one. Drop either
 * annotation and the compiler is perfectly happy: what happens instead is a
 * {@code NoUniqueBeanDefinitionException} on the context, which on Container Apps is a revision
 * that never goes healthy - the same failure mode {@code InjectableConstructorsTest} was written
 * for, and caught the same way.
 *
 * <p>Swapping them silently would be worse than either: the relay would post rows into the outbox
 * it is meant to be draining, and every signup would enqueue a message nothing ever sends.
 */
class OutboxWiringTest {

    @Test
    @DisplayName("the outbox sender is the primary one, so the application queues rather than posts")
    void theOutboxSenderIsPrimary() {
        assertThat(OutboxEmailSender.class).hasAnnotation(Primary.class);
        assertThat(EmailSender.class).isAssignableFrom(OutboxEmailSender.class);
    }

    @Test
    @DisplayName("the transport bean is named, and the two classes below the queue ask for it by name")
    void theTransportIsTakenByName() {
        Method transportBean = Arrays.stream(EmailConfiguration.class.getMethods())
                .filter(method -> method.isAnnotationPresent(Bean.class))
                .filter(method -> EmailSender.class.equals(method.getReturnType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no EmailSender bean is declared any more"));
        assertThat(transportBean.getAnnotation(Bean.class).value()).containsExactly("mailTransport");

        assertTakesTheTransportByName(OutboxRelay.class);
        // The indicator asks the transport whether mail is configured at all. Handed the primary
        // sender instead it would be asking the outbox, which always answers yes because writing a
        // row always works - and the one status that says "this deployment sends nothing" would
        // never be reported.
        assertTakesTheTransportByName(MailHealthIndicator.class);
    }

    private static void assertTakesTheTransportByName(Class<?> type) {
        Constructor<?> injected = Arrays.stream(type.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + " has no @Autowired constructor"));

        assertThat(Arrays.stream(injected.getParameters())
                .filter(parameter -> parameter.getType().equals(EmailSender.class))
                .map(Parameter::getAnnotations))
                .as("%s takes the transport rather than whichever EmailSender is primary", type.getSimpleName())
                .anySatisfy(annotations -> assertThat(annotations)
                        .anyMatch(annotation -> annotation instanceof Qualifier qualifier
                                && "mailTransport".equals(qualifier.value())));
    }

    @Test
    @DisplayName("the relay is scheduled, because nothing else ever calls it")
    void theRelayRunsOnASchedule() throws NoSuchMethodException {
        Scheduled schedule = OutboxRelay.class.getMethod("deliverPending").getAnnotation(Scheduled.class);

        assertThat(schedule).as("an unscheduled relay is a queue nothing drains").isNotNull();
        assertThat(schedule.fixedRate()).isPositive();
    }
}
