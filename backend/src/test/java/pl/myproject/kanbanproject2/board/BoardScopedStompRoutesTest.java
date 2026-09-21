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

/**
 * {@code BoardScopedRoutesTest}'s rule, one channel over: <b>every STOMP handler takes the caller
 * it has to check.</b>
 *
 * <p>That test scans {@code @RestController} and has held the REST surface honest since the
 * tenancy model landed. Nothing scanned {@code @MessageMapping}, and chat is what grew in the gap
 * — a controller that built its destination out of a {@code roomId} the client supplied and
 * addressed any {@code recipientId} it was handed, in an application where every other route
 * answers "may this caller see this board?" before it answers anything else. A guard that covers
 * one transport and not the other is a guard that says where the next hole will be.
 *
 * <p>A STOMP handler has no {@code PublicPaths} to be excused by: the channel is authenticated at
 * CONNECT, so there is no such thing as a public destination here and the rule has no exceptions.
 * It runs by reflection over the compiled classes, so it costs nothing and needs no broker.
 */
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

    /**
     * The destinations themselves, so that a handler reintroducing a room the client names shows up
     * as a changed assertion rather than as nothing at all. {@code /topic/public} and
     * {@code /topic/room.} are gone from the application; what replaced them is the board's own
     * topic, which {@code BoardSubscriptionInterceptor} already authorises.
     */
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

    /** The scan sees the test classpath too, where suites declare fixtures of their own. */
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
