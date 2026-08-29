package pl.myproject.kanbanproject2.config;

import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build if a controller binds a JPA entity straight off the wire.
 *
 * <p>Lombok puts a public setter on every entity id, so an entity request body lets a create call
 * carry a primary key — {@code save} then issues a merge instead of an insert, overwriting whatever
 * record holds that key and nulling every field the request left out. The same shape hands a client
 * every other column too: {@code completed} would route around the parent-task rule, and a nested
 * {@code subTasks} list would cascade-persist.
 *
 * <p>This is a scan rather than a review note because nothing else catches it: the shape only shows
 * up at runtime, and the frontend never sends an id, so no existing test would go red.
 */
class RequestBodyBindingTest {

    private static final String PRODUCTION_PACKAGE = "pl.myproject.kanbanproject2";

    @Test
    @DisplayName("no @RequestBody binds an @Entity, which would make a create request a merge")
    void noRequestBodyBindsAnEntity() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> offenders = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(PRODUCTION_PACKAGE)) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotationPresent(RequestBody.class)) {
                        continue;
                    }
                    for (Class<?> bound : entitiesIn(parameter.getParameterizedType())) {
                        offenders.add(controller.getSimpleName() + "." + method.getName()
                                + " -> " + bound.getSimpleName());
                    }
                }
            }
        }

        assertThat(offenders)
                .withFailMessage("These bind a JPA entity as a request body, so a client can write "
                        + "any column on it, id included: %s", offenders)
                .isEmpty();
    }

    /** Walks generic arguments too, so a {@code List<Task>} body is caught as readily as a {@code Task}. */
    private static Set<Class<?>> entitiesIn(Type type) {
        Set<Class<?>> found = new java.util.LinkedHashSet<>();
        if (type instanceof Class<?> raw) {
            if (raw.isAnnotationPresent(Entity.class)) {
                found.add(raw);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            found.addAll(entitiesIn(parameterized.getRawType()));
            for (Type argument : parameterized.getActualTypeArguments()) {
                found.addAll(entitiesIn(argument));
            }
        }
        return found;
    }
}
