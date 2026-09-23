package pl.myproject.kanbanproject2.board;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import pl.myproject.kanbanproject2.user.User;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BoardScopedStompRoutesTest {
    private static final String PRODUCTION_PACKAGE = "pl.myproject.kanbanproject2";

    private record Route(Class<?> controller, Method handler, String destination) {
        @Override
        public String toString() {
            return controller.getSimpleName() + "." + handler.getName() + "  ->  " + destination;
        }
    }

    @Test
    @DisplayName("every STOMP handler takes the caller, as a Principal or as the account itself")
    void everyMessageMappingTakesTheCaller() {
        List<Route> unguarded = routes().stream()
                .filter(route -> Arrays.stream(route.handler().getParameterTypes())
                        .noneMatch(type -> Principal.class.isAssignableFrom(type)
                                || User.class.isAssignableFrom(type)))
                .toList();

        assertThat(unguarded)
                .as("a STOMP handler with no caller cannot check who is asking, and the broker will "
                        + "not check for it - take a Principal and resolve it through "
                        + "StompPrincipals, then ask BoardService the same question the REST routes "
                        + "ask")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan finds the handlers - a silent zero would pass the assertion above")
    void theScanFindsSomething() {
        assertThat(routes())
                .as("@MessageMapping handlers found by scanning " + PRODUCTION_PACKAGE)
                .isNotEmpty();
    }

    @Test
    @DisplayName("the application destinations are the four board-scoped chat ones")
    void theDestinationsAreTheOnesBoardScopingCovers() {
        assertThat(routes().stream().map(Route::destination).sorted().toList())
                .containsExactly("/chat.join", "/chat.leave", "/chat.sendMessage",
                        "/chat.sendPrivateMessage");
    }

    private static List<Route> routes() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        List<Route> routes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(PRODUCTION_PACKAGE)) {
            Class<?> controller = load(definition.getBeanClassName());
            if (!isProductionClass(controller)) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                MessageMapping mapping = method.getAnnotation(MessageMapping.class);
                if (mapping != null) {
                    routes.add(new Route(controller, method,
                            mapping.value().length == 0 ? "" : mapping.value()[0]));
                }
            }
        }
        return routes;
    }

    private static boolean isProductionClass(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        return source != null && !source.getLocation().getPath().contains("test-classes");
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("scanned a class that will not load: " + name, e);
        }
    }
}
