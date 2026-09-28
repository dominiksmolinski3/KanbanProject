package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.Repository;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModifyingQueriesFlushFirstTest {
    private static final String PRODUCTION_PACKAGE = "pl.myproject.kanbanproject2";

    @Test
    @DisplayName("a bulk query that clears the persistence context flushes it first")
    void clearingQueriesFlushFirst() {
        List<String> discarding = modifyingQueries().stream()
                .filter(method -> {
                    Modifying modifying = method.getAnnotation(Modifying.class);
                    return modifying.clearAutomatically() && !modifying.flushAutomatically();
                })
                .map(method -> method.getDeclaringClass().getSimpleName() + "." + method.getName())
                .toList();

        assertThat(discarding)
                .as("clearAutomatically without flushAutomatically throws away every change the "
                        + "transaction has not flushed yet: a password change that was saved just "
                        + "before revokeAllFor answered 204 and was never written")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan finds the modifying queries - a silent zero would pass the assertion above")
    void theScanFindsSomething() {
        assertThat(modifyingQueries()).hasSizeGreaterThanOrEqualTo(4);
    }

    private static List<Method> modifyingQueries() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(
                    org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        List<Method> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(PRODUCTION_PACKAGE)) {
            try {
                for (Method method : Class.forName(definition.getBeanClassName()).getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Modifying.class)) {
                        found.add(method);
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return found;
    }
}
