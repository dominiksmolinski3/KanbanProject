package pl.myproject.kanbanproject2.board;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.config.security.PublicPaths;
import pl.myproject.kanbanproject2.user.User;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that makes the ownership model hard to forget: <b>every REST handler either takes the
 * caller, or is on a path served without a token.</b>
 *
 * <p>This is the guard the previous shape of the code had no way to express. Authorization was
 * "the filter chain proved a token exists", so a new route was authorized by default and nobody
 * had to think about it; the one hand-written check on {@code /api/users/{id}} did not generalise
 * because there was nothing to check against. Now the caller is a parameter, and a route that does
 * not ask for one is either public or a hole — and this test decides which, from
 * {@link PublicPaths}, rather than from a list somebody remembered to update.
 *
 * <p>It runs by reflection over the compiled classes rather than by booting a context, so it costs
 * nothing and cannot be skipped for want of a database.
 */
class BoardScopedRoutesTest {

    private static final String PRODUCTION_PACKAGE = "pl.myproject.kanbanproject2";

    /** {@code WebConfig} applies this to every {@code @RestController}; ApiPathPrefixTest pins it. */
    private static final String API_PREFIX = "/api";

    private static final List<Class<? extends Annotation>> MAPPINGS = List.of(
            RequestMapping.class, GetMapping.class, PostMapping.class,
            PutMapping.class, PatchMapping.class, DeleteMapping.class);

    private record Route(Class<?> controller, Method handler, String path) {
        @Override
        public String toString() {
            return controller.getSimpleName() + "." + handler.getName() + "  ->  " + path;
        }
    }

    @Test
    @DisplayName("every authenticated REST handler takes the caller it has to check")
    void everyGuardedRouteTakesThePrincipal() {
        List<Route> unguarded = routes().stream()
                .filter(route -> !PublicPaths.isPublic(route.path()))
                .filter(route -> Arrays.stream(route.handler().getParameterTypes())
                        .noneMatch(User.class::isAssignableFrom))
                .toList();

        assertThat(unguarded)
                .as("a handler with no caller cannot check who is asking, so it is open to every "
                        + "account on the deployment - take @AuthenticationPrincipal User and pass "
                        + "it to the service, or put the path in PublicPaths and say why")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan finds the controllers - a silent zero would pass every assertion above")
    void theScanFindsSomething() {
        assertThat(routes())
                .as("REST handlers found by scanning " + PRODUCTION_PACKAGE)
                .hasSizeGreaterThan(40);
    }

    @Test
    @DisplayName("the public routes are exactly the pre-authentication ones")
    void onlyAuthRoutesArePublic() {
        List<String> publicRoutes = routes().stream()
                .filter(route -> PublicPaths.isPublic(route.path()))
                .map(Route::path)
                .distinct()
                .sorted()
                .toList();

        // Signup, login, verify, resend and the two password-reset routes. Anything else appearing
        // here means a board route has been made reachable without a token.
        assertThat(publicRoutes).allMatch(path -> path.startsWith("/api/auth/"));
        assertThat(publicRoutes).hasSize(PublicPaths.AUTH_ENDPOINTS.length);
    }

    private static List<Route> routes() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Route> routes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(PRODUCTION_PACKAGE)) {
            Class<?> controller = load(definition.getBeanClassName());
            if (!isProductionClass(controller)) {
                continue;
            }
            String base = firstPath(controller.getAnnotation(RequestMapping.class));
            for (Method method : controller.getDeclaredMethods()) {
                mappingPath(method).ifPresent(suffix ->
                        routes.add(new Route(controller, method, join(base, suffix))));
            }
        }
        return routes;
    }

    /**
     * The scan sees the test classpath too, and the suites here declare their own probe
     * controllers to assert the {@code /api} prefix with. Those are fixtures, not routes.
     */
    private static boolean isProductionClass(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        return source != null && !source.getLocation().getPath().contains("test-classes");
    }

    private static java.util.Optional<String> mappingPath(Method method) {
        for (Class<? extends Annotation> type : MAPPINGS) {
            Annotation mapping = method.getAnnotation(type);
            if (mapping != null) {
                return java.util.Optional.of(firstPath(mapping));
            }
        }
        return java.util.Optional.empty();
    }

    /** {@code @GetMapping("/x")} and {@code @GetMapping(value = "/x")} are the same annotation. */
    private static String firstPath(Annotation mapping) {
        if (mapping == null) {
            return "";
        }
        String[] paths = invoke(mapping, "value");
        if (paths.length == 0) {
            paths = invoke(mapping, "path");
        }
        return paths.length == 0 ? "" : paths[0];
    }

    private static String[] invoke(Annotation mapping, String attribute) {
        try {
            return (String[]) mapping.annotationType().getMethod(attribute).invoke(mapping);
        } catch (ReflectiveOperationException e) {
            return new String[0];
        }
    }

    private static String join(String base, String suffix) {
        String path = API_PREFIX + base + suffix;
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("scanned a class that will not load: " + name, e);
        }
    }
}
