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
