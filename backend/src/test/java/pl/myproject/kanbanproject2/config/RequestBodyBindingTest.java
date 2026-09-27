package pl.myproject.kanbanproject2.config;

import jakarta.persistence.Entity;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.mail.MailDeliveryReportController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodyBindingTest {
    private static final String PRODUCTION_PACKAGE = "pl.myproject.kanbanproject2";

    // Event Grid decides that payload's shape, and the route must answer 2xx to anything past its key check.
    private static final Set<Class<?>> UNVALIDATED_BY_DESIGN = Set.of(MailDeliveryReportController.class);

    private record Body(Class<?> controller, Method handler, Parameter parameter) {
        @Override
        public String toString() {
            return controller.getSimpleName() + "." + handler.getName() + " -> "
                    + parameter.getType().getSimpleName();
        }
    }

    @Test
    @DisplayName("no @RequestBody binds an @Entity, which would make a create request a merge")
    void noRequestBodyBindsAnEntity() {
        Set<String> offenders = new TreeSet<>();
        for (Body body : bodies()) {
            for (Class<?> bound : typesIn(body.parameter().getParameterizedType(),
                    raw -> raw.isAnnotationPresent(Entity.class))) {
                offenders.add(body.controller().getSimpleName() + "." + body.handler().getName()
                        + " -> " + bound.getSimpleName());
            }
        }

        assertThat(offenders)
                .withFailMessage("These bind a JPA entity as a request body, so a client can write "
                        + "any column on it, id included: %s", offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("every @RequestBody is validated, with @Valid or a constraint on the parameter")
    void everyRequestBodyIsValidated() {
        List<Body> unvalidated = bodies().stream()
                .filter(body -> !UNVALIDATED_BY_DESIGN.contains(body.controller()))
                .filter(body -> !body.parameter().isAnnotationPresent(Valid.class))
                .filter(body -> Arrays.stream(body.parameter().getAnnotations())
                        .noneMatch(a -> a.annotationType().isAnnotationPresent(Constraint.class)))
                .toList();

        assertThat(unvalidated)
                .as("an unvalidated body reaches the service as the client wrote it: a negative "
                        + "limit is stored and an over-long string is a 500 from the database. "
                        + "Bind a @Valid request record, or put a constraint on the parameter")
                .isEmpty();
    }

    @Test
    @DisplayName("no @RequestBody binds a type that some route answers with")
    void noRequestBodyIsAResponseType() {
        Set<Class<?>> responses = new HashSet<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                responses.addAll(typesIn(method.getGenericReturnType(),
                        raw -> raw.getName().startsWith(PRODUCTION_PACKAGE)));
            }
        }

        List<Body> reused = bodies().stream()
                .filter(body -> responses.contains(body.parameter().getType()))
                .toList();

        assertThat(responses).as("response types found by the scan").hasSizeGreaterThan(20);
        assertThat(reused)
                .as("a response DTO carries fields a client must never set, such as an id or an "
                        + "email, and no validation. Bind a Patch*/Create* request record instead")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan finds the request bodies - a silent zero would pass every assertion above")
    void theScanFindsSomething() {
        assertThat(bodies()).hasSizeGreaterThan(20);
    }

    private static Set<Class<?>> typesIn(Type type, Predicate<Class<?>> wanted) {
        Set<Class<?>> found = new LinkedHashSet<>();
        if (type instanceof Class<?> raw) {
            if (wanted.test(raw)) {
                found.add(raw);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            found.addAll(typesIn(parameterized.getRawType(), wanted));
            for (Type argument : parameterized.getActualTypeArguments()) {
                found.addAll(typesIn(argument, wanted));
            }
        }
        return found;
    }

    private static List<Body> bodies() {
        List<Body> found = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.isAnnotationPresent(RequestBody.class)) {
                        found.add(new Body(controller, method, parameter));
                    }
                }
            }
        }
        return found;
    }

    private static List<Class<?>> controllers() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(PRODUCTION_PACKAGE)) {
            Class<?> controller;
            try {
                controller = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            // Test suites declare probe controllers as nested classes; no production controller is nested.
            if (controller.getEnclosingClass() == null) {
                found.add(controller);
            }
        }
        return found;
    }
}
