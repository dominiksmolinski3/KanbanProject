package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRoutesArePublicTest {
    private static final String AUTH_PREFIX = "/api/auth/";

    @Test
    @DisplayName("every route under /api/auth is public - the client skips the token for the whole prefix")
    void everyAuthRouteIsPublic() {
        Set<String> authRoutes = new TreeSet<>(servedRoutes());
        authRoutes.removeIf(route -> !route.startsWith(AUTH_PREFIX));

        assertThat(authRoutes).as("the scan found the auth controller").hasSizeGreaterThanOrEqualTo(8);
        assertThat(authRoutes)
                .as("apiInterceptor.ts sends anything under %s without a token, so a route there "
                        + "that needs one answers 401 forever - add it to PublicPaths.AUTH_ENDPOINTS, "
                        + "or serve it from outside the prefix, as /api/devices is", AUTH_PREFIX)
                .allMatch(PublicPaths::isPublic);
    }

    @Test
    @DisplayName("every public auth endpoint is a real route under /api/auth")
    void everyPublicAuthEndpointIsServedUnderThePrefix() {
        assertThat(PublicPaths.AUTH_ENDPOINTS)
                .as("outside %s the client would attach a token and, on a 401, renew or end the "
                        + "session of someone who is signing in", AUTH_PREFIX)
                .allMatch(endpoint -> endpoint.startsWith(AUTH_PREFIX));
        assertThat(servedRoutes())
                .as("an entry no controller answers on is permitted for nothing")
                .contains(PublicPaths.AUTH_ENDPOINTS);
    }

    private static Set<String> servedRoutes() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> routes = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("pl.myproject.kanbanproject2")) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException cause) {
                throw new IllegalStateException("scanned a controller that will not load", cause);
            }
            for (String base : pathsOf(AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class))) {
                for (Method method : type.getDeclaredMethods()) {
                    RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                    if (mapping != null) {
                        for (String suffix : pathsOf(mapping)) {
                            routes.add("/api" + base + suffix);
                        }
                    }
                }
            }
        }
        return routes;
    }

    private static String[] pathsOf(RequestMapping mapping) {
        return mapping == null || mapping.value().length == 0 ? new String[]{""} : mapping.value();
    }
}
