package pl.myproject.kanbanproject2.config;

import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Teaches Jackson the difference between a property left out of a PATCH body and one sent as
 * {@code null}.
 *
 * <p>Without this both arrive as a Java {@code null}, so every patch method has to read them as
 * "leave unchanged" — which is why a deadline, a description or a swimlane could be set through the
 * API but never cleared again. Boot picks the module up from the context and registers it on the
 * ObjectMapper it builds.
 */
@Configuration(proxyBeanMethods = false)
public class JsonNullableConfiguration {

    @Bean
    JsonNullableModule jsonNullableModule() {
        return new JsonNullableModule();
    }
}
