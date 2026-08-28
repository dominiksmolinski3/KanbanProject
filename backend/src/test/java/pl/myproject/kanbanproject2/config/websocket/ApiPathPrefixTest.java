package pl.myproject.kanbanproject2.config.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.ParameterizableViewController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import pl.myproject.kanbanproject2.config.SpaRoutes;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the two things the {@code /api} prefix exists to do: keep the REST API off the paths
 * React Router owns, and keep it in one place so no controller can be added without it.
 *
 * <p>Before the prefix, {@code /users} was both a page and an endpoint — the endpoint won, and the
 * page was unreachable on a refresh. The frontend cannot catch a regression here, because its
 * tests stub {@code fetch} and never resolve a URL against this mapping.
 */
class ApiPathPrefixTest {

    private static final String PRODUCTION_PACKAGE = "pl.myproject.kanbanproject2";

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(TestMvc.class);

    @Test
    @DisplayName("a REST controller is served under /api, wherever it declares its own mapping")
    void prefixesRestControllers() {
        contextRunner.run(context -> assertThat(mappedPatterns(context.getBean(RequestMappingHandlerMapping.class)))
                .contains("/api/probe/ping")
                .doesNotContain("/probe/ping"));
    }

    @Test
    @DisplayName("a plain @Controller is left alone, so STOMP destinations keep their paths")
    void leavesPlainControllersAlone() {
        // ChatController is a @Controller carrying @MessageMapping. Prefixing it would rewrite the
        // destinations the STOMP client subscribes to, which the browser resolves, not Spring MVC.
        contextRunner.run(context -> assertThat(mappedPatterns(context.getBean(RequestMappingHandlerMapping.class)))
                .contains("/plain/ping")
                .doesNotContain("/api/plain/ping"));
    }

    @Test
    @DisplayName("no controller writes /api itself, which would double the prefix")
    void noControllerHardcodesThePrefix() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> offenders = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(PRODUCTION_PACKAGE)) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            RequestMapping mapping = type.getAnnotation(RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            for (String path : mapping.value()) {
                if (path.startsWith("/api")) {
                    offenders.add(type.getSimpleName() + " -> " + path);
                }
            }
        }

        assertThat(offenders)
                .withFailMessage("These declare /api themselves and would be served at /api/api/...: %s",
                        offenders)
                .isEmpty();
    }

    @Test
    @DisplayName("every client route forwards to the shell, so a refresh does not 404")
    void forwardsClientRoutesToTheShell() {
        contextRunner.run(context -> {
            SimpleUrlHandlerMapping viewControllers =
                    context.getBean("viewControllerHandlerMapping", SimpleUrlHandlerMapping.class);

            assertThat(viewControllers.getUrlMap()).containsOnlyKeys(SpaRoutes.ALL);
            assertThat(viewControllers.getUrlMap().values())
                    .allSatisfy(handler -> assertThat(((ParameterizableViewController) handler).getViewName())
                            .isEqualTo("forward:/index.html"));
        });
    }

    private static Set<String> mappedPatterns(RequestMappingHandlerMapping mapping) {
        return mapping.getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::getPathPatternsCondition)
                .filter(condition -> condition != null)
                .flatMap(condition -> condition.getPatternValues().stream())
                .collect(Collectors.toSet());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class TestMvc {

        @Bean
        WebConfig webConfig() {
            return new WebConfig();
        }

        @Bean
        ProbeRestController probeRestController() {
            return new ProbeRestController();
        }

        @Bean
        ProbePlainController probePlainController() {
            return new ProbePlainController();
        }
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeRestController {
        @GetMapping("/ping")
        String ping() {
            return "pong";
        }
    }

    @Controller
    @RequestMapping("/plain")
    static class ProbePlainController {
        @GetMapping("/ping")
        String ping() {
            return "pong";
        }
    }
}
