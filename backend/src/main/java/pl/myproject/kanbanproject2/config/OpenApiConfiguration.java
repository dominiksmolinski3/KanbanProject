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

/**
 * The published contract for the routes under {@code /api}, served as JSON at
 * {@code /v3/api-docs}.
 *
 * <p>There are roughly sixty of them and there was no contract at all, which meant every client -
 * this repository's own React app included - learned the API by reading Java. springdoc derives
 * the shapes from the mappings and the DTO records, so nothing here annotates a controller: the
 * records already say what a response looks like, and a second description beside them is a second
 * thing to keep true.
 *
 * <p>What springdoc cannot derive is <strong>which routes need a token</strong>, and that is the
 * whole of this class. A contract that marks every operation as authenticated is wrong about
 * signup and login; one that marks none is wrong about everything else; and either mistake is the
 * kind that is discovered by a client that has already been written. So the requirement is not
 * declared here at all - it is read from {@link PublicPaths}, the same list
 * {@code SecurityConfiguration} builds the filter chain from and {@code JwtAuthenticationFilter}
 * skips on. The spec says what the chain does because both read one list.
 */
@Configuration
public class OpenApiConfiguration {

    /** The name the requirement and the scheme agree on; it appears in the JSON and nowhere else. */
    static final String BEARER_SCHEME = "bearer-jwt";

    /**
     * The version of the <em>contract</em>, not of the jar.
     *
     * <p>Deliberately not the Maven version: the paths carry no version segment, so every build
     * publishing a new contract version would claim a compatibility break on every commit. This
     * moves when the API does.
     */
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

    /**
     * Marks every operation that is not on a public path as needing the bearer token.
     *
     * <p>Applied per operation rather than as one document-level requirement, because the document
     * level cannot be switched off for an individual path without annotating that path - and the
     * routes that would need the annotation are exactly the unauthenticated ones, which is where a
     * mistake costs the most.
     */
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

    /**
     * The operations actually declared on a path.
     *
     * <p>{@link PathItem#readOperations()} returns only the verbs that are present, which is what
     * keeps this from inventing a secured DELETE on a path that has none.
     */
    private static Iterable<Operation> operationsOf(PathItem pathItem) {
        return pathItem.readOperations();
    }
}
