package pl.myproject.kanbanproject2.config.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

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
        contextRunner.run(context -> assertThat(mappedPatterns(context.getBean(RequestMappingHandlerMapping.class)))
                .contains("/plain/ping")
                .doesNotContain("/api/plain/ping"));
    }

    @Test
    @DisplayName("a library's own @RestController keeps its documented path")
    void leavesLibraryControllersAlone() {
        assertThat(WebConfig.prefixedControllers().test(OpenApiWebMvcResource.class))
                .as("the /api prefix must not move a dependency's endpoint")
                .isFalse();
        assertThat(WebConfig.prefixedControllers().test(ProbeRestController.class))
                .as("...while this project's own controllers are exactly what it is for")
                .isTrue();
        assertThat(WebConfig.prefixedControllers().test(ProbePlainController.class))
                .as("a plain @Controller stays out of it whatever package it is in")
                .isFalse();
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
    @DisplayName("nothing forwards to a shell this application no longer has")
    void forwardsNothingToTheShell() {
        contextRunner.run(context -> {
            Object viewControllers = context.getBean("viewControllerHandlerMapping");

            Map<String, ?> forwards = viewControllers instanceof SimpleUrlHandlerMapping mapping
                    ? mapping.getUrlMap()
                    : Map.of();

            assertThat(forwards)
                    .withFailMessage("WebConfig still forwards %s to a shell this jar does not "
                            + "carry; nginx answers the client routes now", forwards.keySet())
                    .isEmpty();
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
