package pl.myproject.kanbanproject2.config.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.HandlerTypePredicate;

import java.util.function.Predicate;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    static final String PRODUCTION_PACKAGE = "pl.myproject.kanbanproject2";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api", prefixedControllers());
    }

    static Predicate<Class<?>> prefixedControllers() {
        return HandlerTypePredicate.forAnnotation(RestController.class)
                .and(HandlerTypePredicate.forBasePackage(PRODUCTION_PACKAGE));
    }

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer
                .defaultContentType(MediaType.APPLICATION_JSON)
                .mediaType("json", MediaType.APPLICATION_JSON);
    }
}