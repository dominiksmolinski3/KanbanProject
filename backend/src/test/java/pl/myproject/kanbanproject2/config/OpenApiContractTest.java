package pl.myproject.kanbanproject2.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import pl.myproject.kanbanproject2.config.security.PublicPaths;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The half of the published contract springdoc cannot derive: which routes need a token.
 *
 * <p>Shapes and paths come from the mappings and the DTO records, so they cannot drift from the
 * code - change a record and the document changes with it. The security requirement has no such
 * anchor. It is a statement <em>about</em> the filter chain written somewhere else, and a wrong
 * one is discovered by whoever wrote a client against it: a contract that marks
 * {@code /api/auth/login} as authenticated sends people looking for a token they are trying to
 * obtain, and one that marks {@code /api/auth/devices} as public describes a 401 as a feature.
 *
 * <p>{@link OpenApiConfiguration} answers that by reading {@link PublicPaths} rather than listing
 * anything, so the two cannot disagree. This test is what checks the reading, and it is written
 * against the traps rather than against the happy path - {@code /auth} is the prefix where
 * "public" and "not public" sit next to each other.
 */
class OpenApiContractTest {

    private final OpenApiConfiguration configuration = new OpenApiConfiguration();

    @Test
    @DisplayName("a route that needs a token says so")
    void securesAuthenticatedRoutes() {
        OpenAPI openApi = customized("/api/tasks", "/api/boards/{id}", "/api/activity");

        assertThat(requirementNamesOn(openApi, "/api/tasks")).containsExactly(OpenApiConfiguration.BEARER_SCHEME);
        assertThat(requirementNamesOn(openApi, "/api/boards/{id}")).containsExactly(OpenApiConfiguration.BEARER_SCHEME);
        assertThat(requirementNamesOn(openApi, "/api/activity")).containsExactly(OpenApiConfiguration.BEARER_SCHEME);
    }

    @Test
    @DisplayName("the routes a caller reaches before they have a token are left unsecured")
    void leavesPublicRoutesAlone() {
        // Every one of these exists for somebody who cannot prove who they are yet: signing up,
        // signing in, redeeming a code from a mailbox, or renewing an access token that has lapsed.
        OpenAPI openApi = customized(
                "/api/auth/signup",
                "/api/auth/login",
                "/api/auth/verify",
                "/api/auth/forgot-password",
                "/api/auth/reset-password",
                "/api/auth/refresh",
                "/api/auth/logout");

        openApi.getPaths().keySet().forEach(path ->
                assertThat(requirementNamesOn(openApi, path))
                        .as("%s is public in the filter chain and the contract should say the same", path)
                        .isEmpty());
    }

    @Test
    @DisplayName("the two authenticated routes under /auth are not swept up with their neighbours")
    void securesTheDeviceRoutes() {
        // The trap this test exists for. `/api/auth/**` reads as "the pre-authentication prefix",
        // and it is - except for these two, which are about an account and so require one. The
        // client-side interceptor made exactly this mistake with exactly this prefix.
        OpenAPI openApi = customized("/api/auth/devices", "/api/auth/devices/{id}");

        assertThat(requirementNamesOn(openApi, "/api/auth/devices"))
                .containsExactly(OpenApiConfiguration.BEARER_SCHEME);
        assertThat(requirementNamesOn(openApi, "/api/auth/devices/{id}"))
                .containsExactly(OpenApiConfiguration.BEARER_SCHEME);
    }

    @Test
    @DisplayName("every verb on a secured path is secured, and no verb is invented on the way")
    void securesEveryDeclaredOperation() {
        PathItem pathItem = new PathItem()
                .get(new Operation())
                .patch(new Operation())
                .delete(new Operation());
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem("/api/tasks/{id}", pathItem));

        configuration.bearerTokenOnAuthenticatedRoutes().customise(openApi);

        assertThat(pathItem.readOperationsMap()).hasSize(3);
        assertThat(pathItem.readOperations())
                .allSatisfy(operation -> assertThat(operation.getSecurity()).hasSize(1));
        assertThat(pathItem.getPut())
                .as("a verb the controller does not declare must not appear in the contract")
                .isNull();
    }

    @Test
    @DisplayName("the requirement names a scheme the document actually defines")
    void theRequirementResolvesToADeclaredScheme() {
        // A requirement referencing a name that is not in components is a document every generator
        // and validator rejects, and nothing but this comparison can see the two halves are one
        // string apart.
        OpenAPI document = configuration.kanbanOpenApi();
        OpenAPI customized = customized("/api/tasks");

        assertThat(document.getComponents().getSecuritySchemes())
                .containsKey(OpenApiConfiguration.BEARER_SCHEME);
        assertThat(requirementNamesOn(customized, "/api/tasks"))
                .isSubsetOf(document.getComponents().getSecuritySchemes().keySet());
    }

    @Test
    @DisplayName("the document says what it is and how to authenticate against it")
    void carriesItsOwnDescription() {
        OpenAPI document = configuration.kanbanOpenApi();

        assertThat(document.getInfo().getTitle()).isEqualTo("Kanban API");
        assertThat(document.getInfo().getVersion()).isEqualTo(OpenApiConfiguration.API_VERSION);
        assertThat(document.getInfo().getDescription())
                .as("the 404-not-403 rule is the first thing a client author gets wrong here")
                .contains("404");
        assertThat(document.getComponents().getSecuritySchemes().get(OpenApiConfiguration.BEARER_SCHEME).getScheme())
                .isEqualTo("bearer");
    }

    @Test
    @DisplayName("a document with no paths is left alone rather than dereferenced")
    void toleratesAnEmptyDocument() {
        // springdoc hands the customizer whatever it has built; a document with no paths is what a
        // context with no mapped controllers produces, which is what several tests here run in.
        assertThatCode(() -> configuration.bearerTokenOnAuthenticatedRoutes().customise(new OpenAPI()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the contract itself is fetchable without a token, or it is not published")
    void theContractIsPublic() {
        assertThat(PublicPaths.DOCS_ENDPOINTS).contains("/v3/api-docs");
        assertThat(PublicPaths.isPublic("/v3/api-docs")).isTrue();
        assertThat(PublicPaths.isPublic("/v3/api-docs/swagger-config")).isTrue();

        // And it is served where springdoc says it is, not under the application's own prefix -
        // see WebConfig, which scopes the /api prefix to this project's package for this reason.
        assertThat(PublicPaths.isPublic("/api/v3/api-docs"))
                .as("a contract at the prefixed path would be a path nothing thinks to ask for, "
                        + "and opening it up would say the prefix scoping had been lost")
                .isFalse();
    }

    /** Runs the customizer over a document holding one GET per path. */
    private OpenAPI customized(String... paths) {
        Paths documentPaths = new Paths();
        for (String path : paths) {
            documentPaths.addPathItem(path, new PathItem().get(new Operation()));
        }
        OpenAPI openApi = new OpenAPI().paths(documentPaths);

        OpenApiCustomizer customizer = configuration.bearerTokenOnAuthenticatedRoutes();
        customizer.customise(openApi);
        return openApi;
    }

    private static List<String> requirementNamesOn(OpenAPI openApi, String path) {
        List<SecurityRequirement> security = openApi.getPaths().get(path).readOperations().getFirst().getSecurity();
        return security == null ? List.of() : security.stream().flatMap(requirement -> requirement.keySet().stream()).toList();
    }
}
