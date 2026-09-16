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

    /** The package the prefix applies to — this application's own controllers and nothing else. */
    static final String PRODUCTION_PACKAGE = "pl.myproject.kanbanproject2";

    /**
     * Puts every REST endpoint under {@code /api} from one place, so no controller carries the
     * prefix itself. This keeps the API off the paths React Router owns — before the prefix,
     * {@code /users} resolved to {@link pl.myproject.kanbanproject2.user.UserController} instead of
     * the page. Scoped to {@link #PRODUCTION_PACKAGE} as well as {@code @RestController}, or an
     * unscoped predicate would quietly move springdoc's own {@code @RestController} contract from
     * {@code /v3/api-docs} to {@code /api/v3/api-docs}.
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api", prefixedControllers());
    }

    /**
     * Package-visible so {@code ApiPathPrefixTest} can ask it about a class directly. Composed with
     * {@code and} rather than built from one {@link HandlerTypePredicate}, because that builder's
     * selectors are <em>alternatives</em>: {@code .annotation(X).basePackage(Y)} means X
     * <strong>or</strong> Y, which would prefix {@code ChatController} and rewrite the STOMP
     * destinations the browser subscribes to.
     */
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