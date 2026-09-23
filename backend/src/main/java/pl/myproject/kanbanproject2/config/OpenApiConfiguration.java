package pl.myproject.kanbanproject2.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.myproject.kanbanproject2.config.security.PublicPaths;

@Configuration
public class OpenApiConfiguration {

    static final String BEARER_SCHEME = "bearer-jwt";

    static final String API_VERSION = "v1";

    @Bean
    public OpenAPI kanbanOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Kanban API")
                        .version(API_VERSION)
                        .description("""
                                Boards with members: being on a board's member list is what grants \
                                access to its columns, swimlanes and tasks. An object on a board \
                                the caller cannot see answers 404 rather than 403, so a 404 here \
                                means either "no such id" or "not yours" and deliberately does not \
                                say which.

                                Authentication is a bearer JWT from POST /api/auth/login or \
                                /api/auth/verify, good for fifteen minutes; POST /api/auth/refresh \
                                exchanges the refresh token for a new pair and spends the one it \
                                was given."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("The access token from a login, verify or refresh response.")));
    }

    @Bean
    public OpenApiCustomizer bearerTokenOnAuthenticatedRoutes() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) -> {
                if (PublicPaths.isPublic(path)) {
                    return;
                }
                for (Operation operation : operationsOf(pathItem)) {
                    operation.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
                }
            });
        };
    }

    private static Iterable<Operation> operationsOf(PathItem pathItem) {
        return pathItem.readOperations();
    }
}
