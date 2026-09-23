package pl.myproject.kanbanproject2.config.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.HandlerTypePredicate;

import java.util.Arrays;
import java.util.List;
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

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {

        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jacksonConverter) {

                List<MediaType> supportedMediaTypes =
                        Arrays.asList(
                                MediaType.APPLICATION_JSON,
                                new MediaType("application", "json", java.nio.charset.StandardCharsets.UTF_8)
                        );

                jacksonConverter.setSupportedMediaTypes(supportedMediaTypes);
            }
        }
    }
}