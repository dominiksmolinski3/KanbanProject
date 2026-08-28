package pl.myproject.kanbanproject2.config.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.method.HandlerTypePredicate;
import pl.myproject.kanbanproject2.config.SpaRoutes;

import java.util.Arrays;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Puts every REST endpoint under {@code /api} from one place, so no controller carries the
     * prefix itself and none can be added without it.
     *
     * <p>This is what keeps the API off the paths React Router owns: the SPA serves {@code /board}
     * and {@code /users}, and before the prefix existed {@code /users} resolved to
     * {@link pl.myproject.kanbanproject2.user.UserController} instead of the page. It also means
     * the Vite dev proxy needs a single {@code /api} entry rather than one per top-level route.
     *
     * <p>The predicate matches {@code @RestController} only, so the STOMP destinations on
     * {@code ChatController} — a plain {@code @Controller} — are left alone.
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api", HandlerTypePredicate.forAnnotation(RestController.class));
    }

    /**
     * Answers a direct hit on a client-side route with the app shell instead of a 404.
     *
     * <p>The bundle is served by Spring from {@code classpath:/static/} in the packaged image, so
     * a browser that asks for {@code /board} — by refreshing, bookmarking or following a shared
     * link — reaches Spring, not React Router. Forwarding to {@code /index.html} hands the request
     * back to the app, which then reads the URL and renders the right screen.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String route : SpaRoutes.ALL) {
            registry.addViewController(route).setViewName("forward:/index.html");
        }
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
                                new MediaType("application", "json", java.nio.charset.StandardCharsets.UTF_8),
                                MediaType.APPLICATION_JSON_UTF8
                        );

                jacksonConverter.setSupportedMediaTypes(supportedMediaTypes);
            }
        }
    }
}