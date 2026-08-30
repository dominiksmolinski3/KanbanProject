package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every Spring-managed bean has to have a constructor Spring can actually pick.
 *
 * <p>The rule is narrow and easy to trip: with exactly one constructor Spring uses it, no
 * annotation needed. Add a second - a package-private one "visible for tests" is the usual way -
 * and Spring stops guessing, looks for {@code @Autowired}, then for a no-arg constructor, and
 * fails with "No default constructor found" when it finds neither.
 *
 * <p>That is not a startup warning. It is the context failing to build, and every bean downstream
 * of it failing with it. {@code AuthRateLimiter} shipped in exactly that state and nothing noticed,
 * because no test in the suite built a context - the rate limiter's own 53 tests construct it
 * directly, which is precisely the path that keeps working while the application cannot start.
 *
 * <p>This runs without a database, so it holds even where a full context test cannot.
 */
class InjectableConstructorsTest {

    private static final String BASE_PACKAGE = "pl.myproject.kanbanproject2";

    /** Classes carrying a stereotype annotation, which is what makes them candidates for scanning. */
    private static List<Class<?>> componentClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Service.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Repository.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Controller.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.web.bind.annotation.RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(org.springframework.context.annotation.Configuration.class));

        var classes = new ArrayList<Class<?>>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                Class<?> type = Class.forName(definition.getBeanClassName());
                if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
                    classes.add(type);
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned but could not load " + definition.getBeanClassName(), e);
            }
        }
        classes.sort(java.util.Comparator.comparing(Class::getName));
        return classes;
    }

    /** Spring's own resolution order, reduced to the question of whether a choice exists. */
    private static boolean hasAnInjectableConstructor(Class<?> type) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();

        if (constructors.length == 1) {
            return true;
        }
        if (Arrays.stream(constructors).anyMatch(c -> c.isAnnotationPresent(Autowired.class))) {
            return true;
        }
        return Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0);
    }

    @Test
    @DisplayName("the scan finds the beans - it is not passing by finding nothing")
    void theScanFindsBeans() {
        assertThat(componentClasses())
                .as("Spring-managed classes under " + BASE_PACKAGE)
                .hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("every Spring-managed class has a constructor Spring can choose")
    void everyBeanHasAnInjectableConstructor() {
        List<String> unresolvable = componentClasses().stream()
                .filter(type -> !hasAnInjectableConstructor(type))
                .map(type -> type.getName() + " has " + type.getDeclaredConstructors().length
                        + " constructors, none annotated @Autowired and none no-arg")
                .toList();

        assertThat(unresolvable)
                .as("classes Spring cannot construct; annotate the injectable constructor "
                        + "with @Autowired, or collapse the extra one")
                .isEmpty();
    }

    @Test
    @DisplayName("the rate limiter specifically - the class this test was written for")
    void theRateLimiterIsConstructible() {
        var type = pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter.class;

        assertThat(type.getDeclaredConstructors())
                .as("the test-visible constructor is still here, so the ambiguity is still real")
                .hasSize(2);
        assertThat(hasAnInjectableConstructor(type))
                .as("and @Autowired is what resolves it")
                .isTrue();
    }
}
